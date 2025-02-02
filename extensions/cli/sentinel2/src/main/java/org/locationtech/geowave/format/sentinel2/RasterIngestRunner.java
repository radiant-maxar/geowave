/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.format.sentinel2;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.processing.AbstractOperation;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.coverage.processing.operation.BandMerge;
import org.geotools.coverage.processing.operation.BandMerge.TransformList;
import org.geotools.coverage.processing.operation.Crop;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.geowave.adapter.raster.RasterUtils;
import org.locationtech.geowave.adapter.raster.adapter.RasterDataAdapter;
import org.locationtech.geowave.adapter.raster.adapter.merge.nodata.NoDataMergeStrategy;
import org.locationtech.geowave.adapter.raster.plugin.GeoWaveGTRasterFormat;
import org.locationtech.geowave.core.cli.api.OperationParams;
import org.locationtech.geowave.core.cli.operations.config.options.ConfigOptions;
import org.locationtech.geowave.core.geotime.util.ExtractGeometryFilterVisitor;
import org.locationtech.geowave.core.geotime.util.ExtractGeometryFilterVisitorResult;
import org.locationtech.geowave.core.geotime.util.GeometryUtils;
import org.locationtech.geowave.core.index.SPIServiceRegistry;
import org.locationtech.geowave.core.store.api.DataStore;
import org.locationtech.geowave.core.store.api.Index;
import org.locationtech.geowave.core.store.api.Writer;
import org.locationtech.geowave.core.store.cli.CLIUtils;
import org.locationtech.geowave.core.store.cli.store.DataStorePluginOptions;
import org.locationtech.geowave.core.store.util.DataStoreUtils;
import org.locationtech.jts.geom.Geometry;
import org.opengis.coverage.grid.GridCoverageReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.Filter;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.parameter.InvalidParameterValueException;
import org.opengis.parameter.ParameterNotFoundException;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import it.geosolutions.jaiext.range.RangeFactory;

public class RasterIngestRunner extends DownloadRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(RasterIngestRunner.class);

  private static Map<String, Sentinel2BandConverterSpi> registeredBandConverters = null;
  protected final List<String> parameters;
  protected Sentinel2RasterIngestCommandLineOptions ingestOptions;
  protected List<SimpleFeature> lastSceneBands = new ArrayList<>();
  protected SimpleFeature lastScene = null;
  protected Template coverageNameTemplate;
  protected final Map<String, Writer<?>> writerCache = new HashMap<>();

  protected String[] bandsIngested;
  protected DataStore store = null;
  protected DataStorePluginOptions dataStorePluginOptions = null;
  protected Index[] indices = null;
  protected Sentinel2ImageryProvider provider;

  public RasterIngestRunner(
      final Sentinel2BasicCommandLineOptions analyzeOptions,
      final Sentinel2DownloadCommandLineOptions downloadOptions,
      final Sentinel2RasterIngestCommandLineOptions ingestOptions,
      final List<String> parameters) {
    super(analyzeOptions, downloadOptions);
    this.ingestOptions = ingestOptions;
    this.parameters = parameters;
  }

  protected void processParameters(final OperationParams params) throws Exception {
    // Ensure we have all the required arguments
    if (parameters.size() != 2) {
      throw new ParameterException("Requires arguments: <store name> <comma delimited index list>");
    }

    final String providerName = sentinel2Options.providerName();
    final String inputStoreName = parameters.get(0);
    final String indexList = parameters.get(1);

    // Get the Sentinel2 provider.
    provider = Sentinel2ImageryProvider.getProvider(providerName);
    if (provider == null) {
      throw new RuntimeException("Unable to find '" + providerName + "' Sentinel2 provider");
    }

    // Config file
    final File configFile = (File) params.getContext().get(ConfigOptions.PROPERTIES_FILE_CONTEXT);

    // Attempt to load input store.
    dataStorePluginOptions =
        CLIUtils.loadStore(inputStoreName, configFile, new JCommander().getConsole());

    store = dataStorePluginOptions.createDataStore();

    // Load the Indices
    indices =
        DataStoreUtils.loadIndices(dataStorePluginOptions.createIndexStore(), indexList).toArray(
            new Index[0]);

    coverageNameTemplate =
        new Template(
            "name",
            new StringReader(ingestOptions.getCoverageName()),
            new Configuration());
  }

  @Override
  protected void runInternal(final OperationParams params) throws Exception {
    try {
      processParameters(params);
      super.runInternal(params);
    } finally {
      for (final Writer<?> writer : writerCache.values()) {
        if (writer != null) {
          writer.close();
        }
      }
    }
  }

  protected RasterBandData getBandData(final SimpleFeature band)
      throws IOException, TemplateException {
    final Map<String, Object> model = new HashMap<>();
    final SimpleFeatureType type = band.getFeatureType();

    for (final AttributeDescriptor descriptor : type.getAttributeDescriptors()) {
      final String name = descriptor.getLocalName();
      final Object value = band.getAttribute(name);
      if (value != null) {
        model.put(name, value);
      }
    }

    final String coverageName =
        FreeMarkerTemplateUtils.processTemplateIntoString(coverageNameTemplate, model);
    final RasterBandData bandData = provider.getCoverage(band, sentinel2Options.getWorkspaceDir());
    GridCoverage2D coverage = bandData.coverage;
    final GridCoverageReader reader = bandData.reader;
    final double nodataValue = bandData.nodataValue;

    if ((ingestOptions.getCoverageConverter() != null)
        && !ingestOptions.getCoverageConverter().trim().isEmpty()) {
      // a converter was supplied, attempt to use it
      final Sentinel2BandConverterSpi converter =
          getConverter(ingestOptions.getCoverageConverter());
      if (converter != null) {
        coverage = converter.convert(coverageName, coverage, band);
      }
    }
    if (ingestOptions.isSubsample()) {
      coverage =
          (GridCoverage2D) RasterUtils.getCoverageOperations().filteredSubsample(
              coverage,
              ingestOptions.getScale(),
              ingestOptions.getScale(),
              null);
    }

    // its unclear whether cropping should be done first or subsampling
    if (ingestOptions.isCropToSpatialConstraint()) {
      boolean cropped = false;
      final Filter filter = sentinel2Options.getCqlFilter();
      if (filter != null) {
        final ExtractGeometryFilterVisitorResult geometryAndCompareOp =
            ExtractGeometryFilterVisitor.getConstraints(
                filter,
                GeoWaveGTRasterFormat.DEFAULT_CRS,
                SceneFeatureIterator.SHAPE_ATTRIBUTE_NAME);

        Geometry geometry = geometryAndCompareOp.getGeometry();
        if (geometry != null) {
          // go ahead and intersect this with the scene geometry
          final Geometry sceneShape =
              (Geometry) band.getAttribute(SceneFeatureIterator.SHAPE_ATTRIBUTE_NAME);
          if (geometry.contains(sceneShape)) {
            cropped = true;
          } else {
            geometry = geometry.intersection(sceneShape);
            final CoverageProcessor processor = CoverageProcessor.getInstance();
            final AbstractOperation op = (AbstractOperation) processor.getOperation("CoverageCrop");
            final ParameterValueGroup params = op.getParameters();
            params.parameter("Source").setValue(coverage);

            try {
              final MathTransform transform =
                  CRS.findMathTransform(
                      GeometryUtils.getDefaultCRS(),
                      coverage.getCoordinateReferenceSystem(),
                      true);
              params.parameter(Crop.CROP_ROI.getName().getCode()).setValue(
                  JTS.transform(geometry, transform));
              params.parameter(Crop.NODATA.getName().getCode()).setValue(
                  RangeFactory.create(nodataValue, nodataValue));
              params.parameter(Crop.DEST_NODATA.getName().getCode()).setValue(
                  new double[] {nodataValue});

              coverage = (GridCoverage2D) op.doOperation(params, null);
              cropped = true;
            } catch (InvalidParameterValueException | ParameterNotFoundException | FactoryException
                | MismatchedDimensionException | TransformException e) {
              LOGGER.warn("Unable to crop image", e);
            }
          }
        }
        if (!cropped) {
          LOGGER.warn(
              "Option to crop spatially was set but no spatial constraints were provided in CQL expression");
        }
      }
    }
    return new RasterBandData(coverageName, coverage, reader, nodataValue);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  protected void nextBand(final SimpleFeature band, final AnalysisInfo analysisInfo) {
    super.nextBand(band, analysisInfo);

    if (ingestOptions.isCoveragePerBand()) {

      // ingest this band
      // convert the simplefeature into a map to resolve the coverage name
      // using a user supplied freemarker template
      try {
        final RasterBandData bandData = getBandData(band);
        final GridCoverage2D coverage = bandData.coverage;
        final String coverageName = bandData.name;
        final GridCoverageReader reader = bandData.reader;
        final double nodataValue = bandData.nodataValue;

        Writer writer = writerCache.get(coverageName);
        final GridCoverage2D nextCov = coverage;

        if (writer == null) {
          final Map<String, String> metadata = new HashMap<>();

          final String[] metadataNames = reader.getMetadataNames();
          if ((metadataNames != null) && (metadataNames.length > 0)) {
            for (final String metadataName : metadataNames) {
              metadata.put(metadataName, reader.getMetadataValue(metadataName));
            }
          }

          final RasterDataAdapter adapter =
              new RasterDataAdapter(
                  coverageName,
                  metadata,
                  nextCov,
                  ingestOptions.getTileSize(),
                  ingestOptions.isCreatePyramid(),
                  ingestOptions.isCreateHistogram(),
                  new double[][] {new double[] {nodataValue}},
                  new NoDataMergeStrategy());
          store.addType(adapter, indices);
          writer = store.createWriter(adapter.getTypeName());
          writerCache.put(coverageName, writer);
        }
        writer.write(nextCov);
      } catch (IOException | TemplateException e) {
        LOGGER.error(
            "Unable to ingest band "
                + band.getID()
                + " because coverage name cannot be resolved from template",
            e);
      }
    } else {
      lastSceneBands.add(band);
    }
  }

  @Override
  protected void lastSceneComplete(final AnalysisInfo analysisInfo) {
    processPreviousScene();
    super.lastSceneComplete(analysisInfo);

    if (!ingestOptions.isSkipMerge()) {
      System.out.println("Merging overlapping tiles...");

      for (final Index index : indices) {
        if (dataStorePluginOptions.createDataStoreOperations().mergeData(
            index,
            dataStorePluginOptions.createAdapterStore(),
            dataStorePluginOptions.createInternalAdapterStore(),
            dataStorePluginOptions.createAdapterIndexMappingStore(),
            dataStorePluginOptions.getFactoryOptions().getStoreOptions().getMaxRangeDecomposition())) {
          System.out.println(
              "Successfully merged overlapping tiles within index '" + index.getName() + "'");
        } else {
          System.err.println(
              "Unable to merge overlapping landsat8 tiles in index '" + index.getName() + "'");
        }
      }
    }

    // Clear all scene files?
    if ((lastScene != null) && !ingestOptions.isRetainImages()) {
      DownloadRunner.cleanDownloadedFiles(lastScene, sentinel2Options.getWorkspaceDir());
    }
    lastScene = null;
  }

  @Override
  protected void nextScene(final SimpleFeature firstBandOfScene, final AnalysisInfo analysisInfo) {
    processPreviousScene();
    super.nextScene(firstBandOfScene, analysisInfo);

    // Clear all scene files?
    if ((lastScene != null) && !ingestOptions.isRetainImages()) {
      DownloadRunner.cleanDownloadedFiles(lastScene, sentinel2Options.getWorkspaceDir());
    }
    lastScene = firstBandOfScene;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  protected void processPreviousScene() {
    if (!ingestOptions.isCoveragePerBand()) {

      // ingest as single image for all bands
      if (!lastSceneBands.isEmpty()) {

        // we are sorting by band name to ensure a consistent order for
        // bands
        final TreeMap<String, RasterBandData> sceneData = new TreeMap<>();
        Writer writer;

        // get coverage info, ensuring that all coverage names are the
        // same
        String coverageName = null;
        for (final SimpleFeature band : lastSceneBands) {
          RasterBandData bandData;
          try {
            bandData = getBandData(band);

            if (coverageName == null) {
              coverageName = bandData.name;
            } else if (!coverageName.equals(bandData.name)) {
              LOGGER.warn(
                  "Unable to use band data as the band coverage name '"
                      + bandData.name
                      + "' is unexpectedly different from default name '"
                      + coverageName
                      + "'");
            }

            final String bandName =
                band.getAttribute(BandFeatureIterator.BAND_ATTRIBUTE_NAME).toString();
            sceneData.put(bandName, bandData);
          } catch (IOException | TemplateException e) {
            LOGGER.warn("Unable to read band data", e);
          }
        }
        if (coverageName == null) {
          LOGGER.warn("No valid bands found for scene");
          lastSceneBands.clear();
          return;
        }

        final GridCoverage2D mergedCoverage;
        if (sceneData.size() == 1) {
          mergedCoverage = sceneData.firstEntry().getValue().coverage;
        } else {
          final CoverageProcessor processor = CoverageProcessor.getInstance();
          final AbstractOperation op = (AbstractOperation) processor.getOperation("BandMerge");
          final ParameterValueGroup params = op.getParameters();
          final List<GridCoverage2D> sources = new ArrayList<>();

          for (final RasterBandData bandData : sceneData.values()) {
            sources.add(bandData.coverage);
          }
          params.parameter("Sources").setValue(sources);
          params.parameter(BandMerge.TRANSFORM_CHOICE).setValue(TransformList.FIRST.toString());

          mergedCoverage = (GridCoverage2D) op.doOperation(params, null);
        }

        final String[] thisSceneBands = sceneData.keySet().toArray(new String[] {});
        if (bandsIngested == null) {
          // this means this is the first scene
          // setup adapter and other required info
          final Map<String, String> metadata = new HashMap<>();

          final double[][] noDataValues = new double[sceneData.size()][];
          int b = 0;

          // merge metadata from all readers
          for (final RasterBandData bandData : sceneData.values()) {
            try {
              final String[] metadataNames = bandData.reader.getMetadataNames();
              if ((metadataNames != null) && (metadataNames.length > 0)) {
                for (final String metadataName : metadataNames) {
                  metadata.put(metadataName, bandData.reader.getMetadataValue(metadataName));
                }
              }
            } catch (final Exception e) {
              LOGGER.warn("Unable to get metadata for coverage '" + coverageName + "'.", e);
            }
            noDataValues[b++] = new double[] {bandData.nodataValue};
          }

          final RasterDataAdapter adapter =
              new RasterDataAdapter(
                  coverageName,
                  metadata,
                  mergedCoverage,
                  ingestOptions.getTileSize(),
                  ingestOptions.isCreatePyramid(),
                  ingestOptions.isCreateHistogram(),
                  noDataValues,
                  new NoDataMergeStrategy());
          store.addType(adapter, indices);
          writer = store.createWriter(adapter.getTypeName());
          writerCache.put(coverageName, writer);
          bandsIngested = thisSceneBands;
        } else if (!Arrays.equals(bandsIngested, thisSceneBands)) {
          LOGGER.warn(
              "The bands in this scene ('"
                  + Arrays.toString(thisSceneBands)
                  + "') differ from the previous scene ('"
                  + Arrays.toString(bandsIngested)
                  + "').  To merge bands all scenes must use the same bands.  Skipping scene'"
                  + lastSceneBands.get(0).getAttribute(
                      SceneFeatureIterator.ENTITY_ID_ATTRIBUTE_NAME)
                  + "'.");
          lastSceneBands.clear();
          return;
        } else {
          writer = writerCache.get(coverageName);
          if (writer == null) {
            LOGGER.warn(
                "Unable to find writer for coverage '"
                    + coverageName
                    + "'.  Skipping scene'"
                    + lastSceneBands.get(0).getAttribute(
                        SceneFeatureIterator.ENTITY_ID_ATTRIBUTE_NAME)
                    + "'.");
            lastSceneBands.clear();
            return;
          }
        }

        writer.write(mergedCoverage);
        lastSceneBands.clear();
      }
    }
  }

  public Sentinel2BandConverterSpi getConverter(final String converterName) {
    final Sentinel2BandConverterSpi converter = getRegisteredConverters().get(converterName);
    if (converter == null) {
      LOGGER.warn("no Sentinel2 converter registered with name '" + converterName + "'");
    }
    return converter;
  }

  private synchronized Map<String, Sentinel2BandConverterSpi> getRegisteredConverters() {
    if (registeredBandConverters == null) {
      registeredBandConverters = new HashMap<>();
      final Iterator<Sentinel2BandConverterSpi> spiIter =
          new SPIServiceRegistry(RasterIngestRunner.class).load(Sentinel2BandConverterSpi.class);
      while (spiIter.hasNext()) {
        final Sentinel2BandConverterSpi converter = spiIter.next();
        registeredBandConverters.put(converter.getName(), converter);
      }
    }
    return registeredBandConverters;
  }
}
