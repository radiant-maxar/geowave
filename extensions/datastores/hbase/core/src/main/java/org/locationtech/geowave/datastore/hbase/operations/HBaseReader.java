/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.datastore.hbase.operations;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Scan.ReadType;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter;
import org.apache.hadoop.hbase.filter.MultiRowRangeFilter.RowRange;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.locationtech.geowave.core.index.ByteArrayRange;
import org.locationtech.geowave.core.index.ByteArrayUtils;
import org.locationtech.geowave.core.index.IndexUtils;
import org.locationtech.geowave.core.index.MultiDimensionalCoordinateRangesArray;
import org.locationtech.geowave.core.index.StringUtils;
import org.locationtech.geowave.core.store.entities.GeoWaveRowIteratorTransformer;
import org.locationtech.geowave.core.store.operations.RangeReaderParams;
import org.locationtech.geowave.core.store.operations.ReaderParams;
import org.locationtech.geowave.core.store.operations.RowReader;
import org.locationtech.geowave.core.store.query.filter.QueryFilter;
import org.locationtech.geowave.datastore.hbase.HBaseRow;
import org.locationtech.geowave.datastore.hbase.filters.FixedCardinalitySkippingFilter;
import org.locationtech.geowave.datastore.hbase.filters.HBaseDistributableFilter;
import org.locationtech.geowave.datastore.hbase.filters.HBaseNumericIndexStrategyFilter;
import org.locationtech.geowave.datastore.hbase.util.HBaseUtils;
import org.locationtech.geowave.mapreduce.splits.RecordReaderParams;
import org.locationtech.geowave.mapreduce.splits.SplitsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.beust.jcommander.internal.Lists;
import com.google.common.collect.Iterators;

public class HBaseReader<T> implements RowReader<T> {
  private static final Logger LOGGER = LoggerFactory.getLogger(HBaseReader.class);

  protected final ReaderParams<T> readerParams;
  private final RecordReaderParams recordReaderParams;
  protected final HBaseOperations operations;
  private final boolean clientSideRowMerging;
  private final GeoWaveRowIteratorTransformer<T> rowTransformer;
  private final Supplier<Scan> scanProvider;

  protected Closeable scanner = null;
  private Iterator<T> scanIt;

  private final boolean wholeRowEncoding;
  private final int partitionKeyLength;

  public HBaseReader(final ReaderParams<T> readerParams, final HBaseOperations operations) {
    this.readerParams = readerParams;
    this.recordReaderParams = null;
    this.operations = operations;

    this.partitionKeyLength = readerParams.getIndex().getIndexStrategy().getPartitionKeyLength();
    this.wholeRowEncoding =
        readerParams.isMixedVisibility() && !readerParams.isServersideAggregation();
    this.clientSideRowMerging = readerParams.isClientsideRowMerging();
    this.rowTransformer = readerParams.getRowTransformer();
    this.scanProvider = createScanProvider(readerParams, operations, this.clientSideRowMerging);

    if (readerParams.isServersideAggregation()) {
      this.scanner = null;
      scanIt = (Iterator) operations.aggregateServerSide(readerParams);
    } else {
      initScanner();
    }
  }

  public HBaseReader(
      final RecordReaderParams recordReaderParams,
      final HBaseOperations operations) {
    this.readerParams = null;
    this.recordReaderParams = recordReaderParams;
    this.operations = operations;

    this.partitionKeyLength =
        recordReaderParams.getIndex().getIndexStrategy().getPartitionKeyLength();
    this.wholeRowEncoding = recordReaderParams.isMixedVisibility();
    this.clientSideRowMerging = recordReaderParams.isClientsideRowMerging();
    this.rowTransformer =
        (GeoWaveRowIteratorTransformer<T>) GeoWaveRowIteratorTransformer.NO_OP_TRANSFORMER;
    this.scanProvider =
        createScanProvider(
            (RangeReaderParams<T>) recordReaderParams,
            operations,
            this.clientSideRowMerging);

    initRecordScanner();
  }

  @Override
  public void close() {
    if (scanner != null) {
      try {
        scanner.close();
      } catch (final IOException e) {
        LOGGER.error("unable to close scanner", e);
      }
    }
  }

  @Override
  public boolean hasNext() {
    if (scanIt != null) {
      return scanIt.hasNext();
    }
    return false;
  }

  @Override
  public T next() {
    if (scanIt != null) { // not aggregation
      return scanIt.next();
    }
    throw new NoSuchElementException();
  }

  protected void initRecordScanner() {
    final FilterList filterList = new FilterList();
    final ByteArrayRange range = SplitsProvider.fromRowRange(recordReaderParams.getRowRange());

    final Scan rscanner = scanProvider.get();

    // TODO all datastores that use the default splitsprovider seem to
    // ignore range.isEndInclusive()
    // and use next prefix for the end of the scan range - this seems likely
    // to be overly inclusive, but doesn't seem to produce extra results for
    // the other datastores within GeoWaveBasicSparkIT, however it does for
    // HBase
    rscanner.setStartRow(range.getStart()).setStopRow(range.getEndAsNextPrefix());

    if (operations.isServerSideLibraryEnabled()) {
      addSkipFilter((RangeReaderParams<T>) recordReaderParams, filterList);
    }

    if (!filterList.getFilters().isEmpty()) {
      if (filterList.getFilters().size() > 1) {
        rscanner.setFilter(filterList);
      } else {
        rscanner.setFilter(filterList.getFilters().get(0));
      }
    }

    Iterable<Result> resultScanner;
    try {
      resultScanner =
          operations.getScannedResults(rscanner, recordReaderParams.getIndex().getName());
      if (resultScanner instanceof ResultScanner) {
        this.scanner = (Closeable) resultScanner;
      }
    } catch (final IOException e) {
      LOGGER.error("Could not get the results from scanner", e);
      this.scanner = null;
      this.scanIt = null;
      return;
    }

    this.scanIt =
        this.rowTransformer.apply(
            Iterators.transform(
                resultScanner.iterator(),
                e -> new HBaseRow(e, partitionKeyLength)));
  }

  protected void initScanner() {
    final FilterList filterList = new FilterList();

    if (operations.isServerSideLibraryEnabled()) {
      // Add distributable filters if requested, this has to be last
      // in the filter list for the dedupe filter to work correctly

      if (readerParams.getFilter() != null) {
        addDistFilter(readerParams, filterList);
      } else {
        addIndexFilter(readerParams, filterList);
      }

      addSkipFilter(readerParams, filterList);
    }

    if (operations.parallelDecodeEnabled()) {
      final HBaseParallelDecoder<T> parallelScanner =
          new HBaseParallelDecoder<>(
              rowTransformer,
              scanProvider,
              operations,
              readerParams.getQueryRanges().getCompositeQueryRanges(),
              partitionKeyLength);

      if (!filterList.getFilters().isEmpty()) {
        if (filterList.getFilters().size() > 1) {
          parallelScanner.setFilter(filterList);
        } else {
          parallelScanner.setFilter(filterList.getFilters().get(0));
        }
      }
      try {
        operations.startParallelScan(parallelScanner, readerParams.getIndex().getName());
        scanner = parallelScanner;
      } catch (final Exception e) {
        LOGGER.error("Could not get the results from scanner", e);
        this.scanner = null;
        this.scanIt = null;
        return;
      }
      this.scanIt = parallelScanner;
    } else {
      final Scan multiScanner = getMultiScanner(filterList);
      try {
        final Iterable<Result> iterable =
            operations.getScannedResults(multiScanner, readerParams.getIndex().getName());
        if (iterable instanceof ResultScanner) {
          this.scanner = (ResultScanner) iterable;
        }
        this.scanIt = getScanIterator(iterable.iterator());
      } catch (final Exception e) {
        LOGGER.error("Could not get the results from scanner", e);
        this.scanner = null;
        this.scanIt = null;
        return;
      }
    }
  }

  protected Iterator<T> getScanIterator(final Iterator<Result> iterable) {
    return rowTransformer.apply(
        Iterators.transform(iterable, e -> new HBaseRow(e, partitionKeyLength)));
  }

  private void addSkipFilter(final RangeReaderParams<T> params, final FilterList filterList) {
    // Add skipping filter if requested
    if (params.getMaxResolutionSubsamplingPerDimension() != null) {
      if (params.getMaxResolutionSubsamplingPerDimension().length != params.getIndex().getIndexStrategy().getOrderedDimensionDefinitions().length) {
        LOGGER.warn(
            "Unable to subsample for table '"
                + params.getIndex().getName()
                + "'. Subsample dimensions = "
                + params.getMaxResolutionSubsamplingPerDimension().length
                + " when indexed dimensions = "
                + params.getIndex().getIndexStrategy().getOrderedDimensionDefinitions().length);
      } else {
        final int cardinalityToSubsample =
            IndexUtils.getBitPositionFromSubsamplingArray(
                params.getIndex().getIndexStrategy(),
                params.getMaxResolutionSubsamplingPerDimension());

        final FixedCardinalitySkippingFilter skippingFilter =
            new FixedCardinalitySkippingFilter(cardinalityToSubsample);
        filterList.addFilter(skippingFilter);
      }
    }
  }

  private void addDistFilter(final ReaderParams<T> params, final FilterList filterList) {
    final HBaseDistributableFilter hbdFilter = new HBaseDistributableFilter();

    if (wholeRowEncoding) {
      hbdFilter.setWholeRowFilter(true);
    }

    hbdFilter.setPartitionKeyLength(partitionKeyLength);

    final List<QueryFilter> distFilters = Lists.newArrayList();
    distFilters.add(params.getFilter());
    hbdFilter.init(
        distFilters,
        params.getIndex().getIndexModel(),
        params.getAdditionalAuthorizations());

    filterList.addFilter(hbdFilter);
  }

  private void addIndexFilter(final ReaderParams<T> params, final FilterList filterList) {
    final List<MultiDimensionalCoordinateRangesArray> coords = params.getCoordinateRanges();
    if ((coords != null) && !coords.isEmpty()) {
      final HBaseNumericIndexStrategyFilter numericIndexFilter =
          new HBaseNumericIndexStrategyFilter(
              params.getIndex().getIndexStrategy(),
              coords.toArray(new MultiDimensionalCoordinateRangesArray[] {}));
      filterList.addFilter(numericIndexFilter);
    }
  }

  protected Scan getMultiScanner(final FilterList filterList) {
    // Single scan w/ multiple ranges
    final Scan multiScanner = scanProvider.get();
    final List<ByteArrayRange> ranges = readerParams.getQueryRanges().getCompositeQueryRanges();

    final MultiRowRangeFilter filter = operations.getMultiRowRangeFilter(ranges);
    if (filter != null) {
      filterList.addFilter(filter);

      final List<RowRange> rowRanges = filter.getRowRanges();
      multiScanner.withStartRow(rowRanges.get(0).getStartRow());

      final RowRange stopRowRange = rowRanges.get(rowRanges.size() - 1);
      byte[] stopRowExclusive;
      if (stopRowRange.isStopRowInclusive()) {
        // because the end is always exclusive, to make an inclusive
        // stop row into exlusive all we need to do is add a traling 0
        stopRowExclusive = HBaseUtils.getInclusiveEndKey(stopRowRange.getStopRow());
      } else {
        stopRowExclusive = stopRowRange.getStopRow();
      }
      multiScanner.withStopRow(stopRowExclusive);
    }
    if ((readerParams.getLimit() != null) && (readerParams.getLimit() > 0)) {
      multiScanner.setReadType(ReadType.PREAD);
      multiScanner.setLimit(readerParams.getLimit());
    }
    return multiScanner;
  }

  private Supplier<Scan> createScanProvider(
      final RangeReaderParams<T> readerParams,
      final HBaseOperations operations,
      final boolean clientSideRowMerging) {
    final Authorizations authorizations;
    if ((readerParams.getAdditionalAuthorizations() != null)
        && (readerParams.getAdditionalAuthorizations().length > 0)) {
      authorizations = new Authorizations(readerParams.getAdditionalAuthorizations());
    } else {
      authorizations = null;
    }
    final int caching = operations.getScanCacheSize();
    final boolean cacheBlocks = operations.isEnableBlockCache();
    final Integer limit = readerParams.getLimit();
    final List<byte[]> families = Lists.newArrayList();
    if ((readerParams.getAdapterIds() != null) && (readerParams.getAdapterIds().length > 0)) {
      for (final Short adapterId : readerParams.getAdapterIds()) {
        // TODO: This prevents the client from sending bad
        // column family
        // requests to hbase. There may be a more efficient way
        // to do
        // this, via the datastore's AIM store.

        if (operations.verifyColumnFamily(
            adapterId,
            true, // because they're not added
            readerParams.getIndex().getName(),
            false)) {
          families.add(StringUtils.stringToBinary(ByteArrayUtils.shortToString(adapterId)));
        } else {
          LOGGER.warn(
              "Adapter ID: "
                  + adapterId
                  + " not found in table: "
                  + readerParams.getIndex().getName());
        }
      }
    }
    return new Supplier<Scan>() {

      @Override
      public Scan get() {
        final Scan scanner = new Scan();

        if (authorizations != null) {
          scanner.setAuthorizations(authorizations);
        }

        // Performance tuning per store options
        scanner.setCaching(caching);
        scanner.setCacheBlocks(cacheBlocks);

        if ((readerParams.getLimit() != null) && (readerParams.getLimit() > 0)) {
          scanner.setReadType(ReadType.PREAD);
          scanner.setLimit(readerParams.getLimit());
        }
        // Only return the most recent version, unless merging
        if (clientSideRowMerging) {
          scanner.readVersions(HBaseOperations.MERGING_MAX_VERSIONS);
        } else {
          scanner.readVersions(HBaseOperations.DEFAULT_MAX_VERSIONS);
        }

        for (final byte[] family : families) {
          scanner.addFamily(family);
        }

        if ((limit != null) && (limit > 0) && (limit < scanner.getBatch())) {
          scanner.setBatch(limit);
        }

        return scanner;
      }
    };
  }
}
