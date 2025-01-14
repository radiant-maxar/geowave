/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.core.store.index;

import org.locationtech.geowave.core.cli.api.DefaultPluginOptions;
import org.locationtech.geowave.core.cli.api.PluginOptions;
import org.locationtech.geowave.core.index.CompoundIndexStrategy;
import org.locationtech.geowave.core.index.simple.HashKeyIndexStrategy;
import org.locationtech.geowave.core.index.simple.RoundRobinKeyIndexStrategy;
import org.locationtech.geowave.core.store.api.DataStore;
import org.locationtech.geowave.core.store.api.Index;
import org.locationtech.geowave.core.store.operations.remote.options.BasicIndexOptions;
import org.locationtech.geowave.core.store.spi.DimensionalityTypeOptions;
import org.locationtech.geowave.core.store.spi.DimensionalityTypeProviderSpi;
import org.locationtech.geowave.core.store.spi.DimensionalityTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;

/**
 * This class is responsible for loading index SPI plugins and populating parameters delegate with
 * relevant options for that index.
 */
public class IndexPluginOptions extends DefaultPluginOptions implements PluginOptions {

  public static final String INDEX_PROPERTY_NAMESPACE = "index";
  public static final String DEFAULT_PROPERTY_NAMESPACE = "indexdefault";

  private static final Logger LOGGER = LoggerFactory.getLogger(IndexPluginOptions.class);

  private String indexType;

  private String indexName = null;

  @ParametersDelegate
  private BasicIndexOptions basicIndexOptions = new BasicIndexOptions();

  // This is the plugin loaded from SPI based on "type"
  private DimensionalityTypeProviderSpi indexPlugin = null;

  // These are the options loaded from indexPlugin based on "type"
  @ParametersDelegate
  private DimensionalityTypeOptions indexOptions = null;

  /** Constructor */
  public IndexPluginOptions() {}

  public void setBasicIndexOptions(final BasicIndexOptions basicIndexOptions) {
    this.basicIndexOptions = basicIndexOptions;
  }

  @Override
  public void selectPlugin(final String qualifier) {
    // Load the Index options.
    indexType = qualifier;
    if (qualifier != null) {
      indexPlugin = DimensionalityTypeRegistry.getSelectedDimensionalityProvider(qualifier);
      if (indexPlugin == null) {
        throw new ParameterException("Unknown index type specified");
      }
      indexOptions = indexPlugin.createOptions();
    } else {
      indexPlugin = null;
      indexOptions = null;
    }
  }

  public DimensionalityTypeOptions getDimensionalityOptions() {
    return indexOptions;
  }

  public void setDimensionalityTypeOptions(final DimensionalityTypeOptions indexOptions) {
    this.indexOptions = indexOptions;
  }

  @Override
  public String getType() {
    return indexType;
  }

  public int getNumPartitions() {
    return basicIndexOptions.getNumPartitions();
  }

  public void setName(final String indexName) {
    this.indexName = indexName;
  }

  public String getName() {
    return indexName;
  }

  public PartitionStrategy getPartitionStrategy() {
    return basicIndexOptions.getPartitionStrategy();
  }

  public BasicIndexOptions getBasicIndexOptions() {
    return basicIndexOptions;
  }

  public DimensionalityTypeProviderSpi getIndexPlugin() {
    return indexPlugin;
  }

  public Index createIndex(final DataStore dataStore) {
    final Index index = indexPlugin.createIndex(dataStore, indexOptions);
    return wrapIndexWithOptions(index, this);
  }

  static Index wrapIndexWithOptions(final Index index, final IndexPluginOptions options) {
    Index retVal = index;
    if ((options.basicIndexOptions.getNumPartitions() > 1)
        && options.basicIndexOptions.getPartitionStrategy().equals(PartitionStrategy.ROUND_ROBIN)) {
      retVal =
          new CustomNameIndex(
              new CompoundIndexStrategy(
                  new RoundRobinKeyIndexStrategy(options.basicIndexOptions.getNumPartitions()),
                  index.getIndexStrategy()),
              index.getIndexModel(),
              index.getName()
                  + "_"
                  + PartitionStrategy.ROUND_ROBIN.name()
                  + "_"
                  + options.basicIndexOptions.getNumPartitions());
    } else if (options.basicIndexOptions.getNumPartitions() > 1) {
      // default to round robin partitioning (none is not valid if there
      // are more than 1 partition)
      if (options.basicIndexOptions.getPartitionStrategy().equals(PartitionStrategy.NONE)) {
        LOGGER.warn(
            "Partition strategy is necessary when using more than 1 partition, defaulting to 'hash' partitioning.");
      }
      retVal =
          new CustomNameIndex(
              new CompoundIndexStrategy(
                  new HashKeyIndexStrategy(options.basicIndexOptions.getNumPartitions()),
                  index.getIndexStrategy()),
              index.getIndexModel(),
              index.getName()
                  + "_"
                  + PartitionStrategy.HASH.name()
                  + "_"
                  + options.basicIndexOptions.getNumPartitions());
    }
    if ((options.getName() != null) && (options.getName().length() > 0)) {
      retVal =
          new CustomNameIndex(retVal.getIndexStrategy(), retVal.getIndexModel(), options.getName());
    }
    return retVal;
  }

  public static String getIndexNamespace(final String name) {
    return String.format("%s.%s", INDEX_PROPERTY_NAMESPACE, name);
  }

  public static enum PartitionStrategy {
    NONE, HASH, ROUND_ROBIN;

    // converter that will be used later
    public static PartitionStrategy fromString(final String code) {

      for (final PartitionStrategy output : PartitionStrategy.values()) {
        if (output.toString().equalsIgnoreCase(code)) {
          return output;
        }
      }

      return null;
    }
  }
}
