/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.analytic.mapreduce.kmeans;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.hadoop.io.ObjectWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.locationtech.geowave.analytic.AnalyticItemWrapper;
import org.locationtech.geowave.analytic.AnalyticItemWrapperFactory;
import org.locationtech.geowave.analytic.ScopedJobConfiguration;
import org.locationtech.geowave.analytic.SimpleFeatureItemWrapperFactory;
import org.locationtech.geowave.analytic.clustering.NestedGroupCentroidAssignment;
import org.locationtech.geowave.analytic.extract.CentroidExtractor;
import org.locationtech.geowave.analytic.extract.SimpleFeatureCentroidExtractor;
import org.locationtech.geowave.analytic.param.CentroidParameters;
import org.locationtech.geowave.analytic.param.GlobalParameters;
import org.locationtech.geowave.analytic.param.SampleParameters;
import org.locationtech.geowave.analytic.sample.function.RandomSamplingRankFunction;
import org.locationtech.geowave.analytic.sample.function.SamplingRankFunction;
import org.locationtech.geowave.core.geotime.index.SpatialDimensionalityTypeProvider;
import org.locationtech.geowave.core.geotime.index.SpatialOptions;
import org.locationtech.geowave.core.index.ByteArray;
import org.locationtech.geowave.core.index.StringUtils;
import org.locationtech.geowave.mapreduce.GeoWaveWritableInputMapper;
import org.locationtech.geowave.mapreduce.GeoWaveWritableInputReducer;
import org.locationtech.geowave.mapreduce.input.GeoWaveInputKey;
import org.locationtech.geowave.mapreduce.output.GeoWaveOutputKey;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples a random 'k' number of features from a population of geospatial features PER GROUP.
 * Outputs the samples in SimpleFeatures. Sampling is achieved by picking the top ranked input
 * objects. Rank is determined by a sample function implementing {@link SamplingRankFunction}.
 *
 * <p>The input features should have a groupID set if they intend to be sampled by group.
 *
 * <p>Keys are partitioned by the group ID in an attempt to process each group in a separate
 * reducer.
 *
 * <p>Sampled features are written to as a new SimpleFeature to a data store. The SimpleFeature
 * contains attributes:
 *
 * <!-- @formatter:off -->
 *     <p>name - data id of the sampled point
 *     <p>weight - can be anything including the sum of all assigned feature distances
 *     <p>geometry - geometry of the sampled features
 *     <p>count - to hold the number of assigned features
 *     <p>groupID - the assigned group ID to the input objects
 *     
 *     <p>Properties:
 *     <p>"KSamplerMapReduce.Sample.SampleSize" - number of input objects to sample. defaults to 1.
 *     <p>"KSamplerMapReduce.Sample.DataTypeId" - Id of the data type to store the k samples -
 *     defaults to "centroids"
 *     <p>"KSamplerMapReduce.Centroid.ExtractorClass" - extracts a centroid from an item. This
 *     parameter allows customization of determining one or more representative centroids for a
 *     geometry.
 *     <p>"KSamplerMapReduce.Sample.IndexId" - The Index ID used for output simple features.
 *     <p>"KSamplerMapReduce.Sample.SampleRankFunction" - An implementation of {@link
 *     SamplingRankFunction} used to rank the input object.
 *     <p>"KSamplerMapReduce.Centroid.ZoomLevel" - Sets an attribute on the sampled objects
 *     recording a zoom level used in the sampling process. The interpretation of the attribute is
 *     not specified or assumed.
 *     <p>"KSamplerMapReduce.Global.BatchId" ->the id of the batch; defaults to current time in
 *     millis (for range comparisons)
 *     <p>"KSamplerMapReduce.Centroid.WrapperFactoryClass" -> {@link AnalyticItemWrapperFactory} to
 *     extract non-geometric dimensions
 * <!-- @formatter:on -->
 */
public class KSamplerMapReduce {
  protected static final Logger LOGGER = LoggerFactory.getLogger(KSamplerMapReduce.class);

  public static class SampleMap<T> extends
      GeoWaveWritableInputMapper<GeoWaveInputKey, ObjectWritable> {

    protected GeoWaveInputKey outputKey = new GeoWaveInputKey();
    private final KeyManager keyManager = new KeyManager();
    private SamplingRankFunction<T> samplingFunction;
    private ObjectWritable currentValue;
    private AnalyticItemWrapperFactory<Object> itemWrapperFactory;
    private int sampleSize = 1;
    private NestedGroupCentroidAssignment<Object> nestedGroupCentroidAssigner;

    // Override parent since there is not need to decode the value.
    @Override
    protected void mapWritableValue(
        final GeoWaveInputKey key,
        final ObjectWritable value,
        final Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context)
        throws IOException, InterruptedException {
      // cached for efficiency since the output is the input object
      // the de-serialized input object is only used for sampling.
      // For simplicity, allow the de-serialization to occur in all cases,
      // even though some sampling
      // functions do not inspect the input object.
      currentValue = value;
      super.mapWritableValue(key, value, context);
    }

    @Override
    protected void mapNativeValue(
        final GeoWaveInputKey key,
        final Object value,
        final org.apache.hadoop.mapreduce.Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context)
        throws IOException, InterruptedException {
      @SuppressWarnings("unchecked")
      final double rank = samplingFunction.rank(sampleSize, (T) value);
      if (rank > 0.0000000001) {
        final AnalyticItemWrapper<Object> wrapper = itemWrapperFactory.create(value);
        outputKey.setDataId(
            new ByteArray(
                keyManager.putData(
                    nestedGroupCentroidAssigner.getGroupForLevel(wrapper),
                    1.0 - rank, // sorts
                    // in
                    // ascending
                    // order
                    key.getDataId().getBytes())));
        outputKey.setInternalAdapterId(key.getInternalAdapterId());
        outputKey.setGeoWaveKey(key.getGeoWaveKey());
        context.write(outputKey, currentValue);
      }
    }

    @Override
    protected void setup(
        final Mapper<GeoWaveInputKey, ObjectWritable, GeoWaveInputKey, ObjectWritable>.Context context)
        throws IOException, InterruptedException {
      super.setup(context);

      final ScopedJobConfiguration config =
          new ScopedJobConfiguration(
              context.getConfiguration(),
              KSamplerMapReduce.class,
              KSamplerMapReduce.LOGGER);
      sampleSize = config.getInt(SampleParameters.Sample.SAMPLE_SIZE, 1);

      try {
        nestedGroupCentroidAssigner =
            new NestedGroupCentroidAssignment<>(
                context,
                KSamplerMapReduce.class,
                KSamplerMapReduce.LOGGER);
      } catch (final Exception e1) {
        throw new IOException(e1);
      }

      try {
        samplingFunction =
            config.getInstance(
                SampleParameters.Sample.SAMPLE_RANK_FUNCTION,
                SamplingRankFunction.class,
                RandomSamplingRankFunction.class);

        samplingFunction.initialize(context, KSamplerMapReduce.class, KSamplerMapReduce.LOGGER);
      } catch (final Exception e1) {
        throw new IOException(e1);
      }
      try {
        itemWrapperFactory =
            config.getInstance(
                CentroidParameters.Centroid.WRAPPER_FACTORY_CLASS,
                AnalyticItemWrapperFactory.class,
                SimpleFeatureItemWrapperFactory.class);

        itemWrapperFactory.initialize(context, KSamplerMapReduce.class, KSamplerMapReduce.LOGGER);
      } catch (final Exception e1) {
        throw new IOException(e1);
      }
    }
  }

  public static class SampleReducer<T> extends GeoWaveWritableInputReducer<GeoWaveOutputKey, T> {

    private int maxCount = 1;
    private CentroidExtractor<T> centroidExtractor;
    private AnalyticItemWrapperFactory<T> itemWrapperFactory;
    private String sampleDataTypeName = null;
    private String[] indexNames;
    private int zoomLevel = 1;
    private String batchID;
    private final Map<String, Integer> outputCounts = new HashMap<>();

    @Override
    protected void reduceNativeValues(
        final GeoWaveInputKey key,
        final Iterable<Object> values,
        final Reducer<GeoWaveInputKey, ObjectWritable, GeoWaveOutputKey, T>.Context context)
        throws IOException, InterruptedException {

      final String groupID = KeyManager.getGroupAsString(key.getDataId().getBytes());

      for (final Object value : values) {
        final AnalyticItemWrapper<T> sampleItem = itemWrapperFactory.create((T) value);
        Integer outputCount = outputCounts.get(groupID);
        outputCount = outputCount == null ? Integer.valueOf(0) : outputCount;
        if ((outputCount == null) || (outputCount < maxCount)) {

          final AnalyticItemWrapper<T> centroid = createCentroid(groupID, sampleItem);
          if (centroid != null) {
            context.write(
                new GeoWaveOutputKey(sampleDataTypeName, indexNames),
                centroid.getWrappedItem());
            outputCount++;
            outputCounts.put(groupID, outputCount);
          }
        }
      }
    }

    private AnalyticItemWrapper<T> createCentroid(
        final String groupID,
        final AnalyticItemWrapper<T> item) {
      final Point point = centroidExtractor.getCentroid(item.getWrappedItem());
      final AnalyticItemWrapper<T> nextCentroid =
          itemWrapperFactory.createNextItem(
              item.getWrappedItem(),
              groupID,
              point.getCoordinate(),
              item.getExtraDimensions(),
              item.getDimensionValues());

      nextCentroid.setBatchID(batchID);
      nextCentroid.setGroupID(groupID);
      nextCentroid.setZoomLevel(zoomLevel);
      return nextCentroid;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void setup(
        final Reducer<GeoWaveInputKey, ObjectWritable, GeoWaveOutputKey, T>.Context context)
        throws IOException, InterruptedException {
      super.setup(context);

      final ScopedJobConfiguration config =
          new ScopedJobConfiguration(
              context.getConfiguration(),
              KSamplerMapReduce.class,
              KSamplerMapReduce.LOGGER);

      maxCount = config.getInt(SampleParameters.Sample.SAMPLE_SIZE, 1);

      zoomLevel = config.getInt(CentroidParameters.Centroid.ZOOM_LEVEL, 1);

      sampleDataTypeName = config.getString(SampleParameters.Sample.DATA_TYPE_NAME, "sample");

      batchID = config.getString(GlobalParameters.Global.BATCH_ID, UUID.randomUUID().toString());

      final String indexName =
          config.getString(
              SampleParameters.Sample.INDEX_NAME,
              SpatialDimensionalityTypeProvider.createIndexFromOptions(
                  new SpatialOptions()).getName());
      indexNames = new String[] {indexName};
      try {
        centroidExtractor =
            config.getInstance(
                CentroidParameters.Centroid.EXTRACTOR_CLASS,
                CentroidExtractor.class,
                SimpleFeatureCentroidExtractor.class);
      } catch (final Exception e1) {
        throw new IOException(e1);
      }

      try {
        itemWrapperFactory =
            config.getInstance(
                CentroidParameters.Centroid.WRAPPER_FACTORY_CLASS,
                AnalyticItemWrapperFactory.class,
                SimpleFeatureItemWrapperFactory.class);

        itemWrapperFactory.initialize(context, KSamplerMapReduce.class, KSamplerMapReduce.LOGGER);
      } catch (final Exception e1) {

        throw new IOException(e1);
      }
    }
  }

  public static class SampleKeyPartitioner extends Partitioner<GeoWaveInputKey, ObjectWritable> {
    @Override
    public int getPartition(
        final GeoWaveInputKey key,
        final ObjectWritable val,
        final int numPartitions) {
      final byte[] grpIDInBytes = KeyManager.getGroup(key.getDataId().getBytes());
      final int partition = hash(grpIDInBytes) % numPartitions;
      return partition;
    }

    private int hash(final byte[] data) {
      int code = 1;
      int i = 0;
      for (final byte b : data) {
        code += b * Math.pow(31, data.length - 1 - (i++));
      }
      return code;
    }
  }

  private static class KeyManager {
    private ByteBuffer keyBuffer = ByteBuffer.allocate(64);

    private static String getGroupAsString(final byte[] data) {
      return new String(getGroup(data), StringUtils.getGeoWaveCharset());
    }

    private static byte[] getGroup(final byte[] data) {
      final ByteBuffer buffer = ByteBuffer.wrap(data);
      buffer.getDouble();
      final int len = buffer.getInt();
      return Arrays.copyOfRange(data, buffer.position(), (buffer.position() + len));
    }

    private byte[] putData(final String groupID, final double weight, final byte[] dataIdBytes) {
      keyBuffer.rewind();
      final byte[] groupIDBytes = groupID.getBytes(StringUtils.getGeoWaveCharset());
      // try to reuse
      final int size = dataIdBytes.length + 16 + groupIDBytes.length;
      if (keyBuffer.capacity() < size) {
        keyBuffer = ByteBuffer.allocate(size);
      }
      keyBuffer.putDouble(weight);
      keyBuffer.putInt(groupIDBytes.length);
      keyBuffer.put(groupIDBytes);
      keyBuffer.putInt(dataIdBytes.length);
      keyBuffer.put(dataIdBytes);
      return keyBuffer.array();
    }
  }
}
