/**
 * Copyright (c) 2013-2020 Contributors to the Eclipse Foundation
 *
 * <p> See the NOTICE file distributed with this work for additional information regarding copyright
 * ownership. All rights reserved. This program and the accompanying materials are made available
 * under the terms of the Apache License, Version 2.0 which accompanies this distribution and is
 * available at http://www.apache.org/licenses/LICENSE-2.0.txt
 */
package org.locationtech.geowave.core.index.sfc.tiered;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.locationtech.geowave.core.index.ByteArrayRange;
import org.locationtech.geowave.core.index.ByteArrayUtils;
import org.locationtech.geowave.core.index.FloatCompareUtils;
import org.locationtech.geowave.core.index.HierarchicalNumericIndexStrategy;
import org.locationtech.geowave.core.index.IndexMetaData;
import org.locationtech.geowave.core.index.IndexUtils;
import org.locationtech.geowave.core.index.InsertionIds;
import org.locationtech.geowave.core.index.Mergeable;
import org.locationtech.geowave.core.index.MultiDimensionalCoordinateRanges;
import org.locationtech.geowave.core.index.MultiDimensionalCoordinates;
import org.locationtech.geowave.core.index.QueryRanges;
import org.locationtech.geowave.core.index.SinglePartitionInsertionIds;
import org.locationtech.geowave.core.index.SinglePartitionQueryRanges;
import org.locationtech.geowave.core.index.StringUtils;
import org.locationtech.geowave.core.index.VarintUtils;
import org.locationtech.geowave.core.index.dimension.NumericDimensionDefinition;
import org.locationtech.geowave.core.index.dimension.bin.BinRange;
import org.locationtech.geowave.core.index.numeric.BinnedNumericDataset;
import org.locationtech.geowave.core.index.numeric.MultiDimensionalNumericData;
import org.locationtech.geowave.core.index.persist.PersistenceUtils;
import org.locationtech.geowave.core.index.sfc.RangeDecomposition;
import org.locationtech.geowave.core.index.sfc.SpaceFillingCurve;
import org.locationtech.geowave.core.index.sfc.binned.BinnedSFCUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableBiMap.Builder;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * This class uses multiple SpaceFillingCurve objects, one per tier, to represent a single cohesive
 * index strategy with multiple precisions
 */
public class TieredSFCIndexStrategy implements HierarchicalNumericIndexStrategy {
  private static final Logger LOGGER = LoggerFactory.getLogger(TieredSFCIndexStrategy.class);
  private static final int DEFAULT_MAX_ESTIMATED_DUPLICATE_IDS_PER_DIMENSION = 2;
  protected static final int DEFAULT_MAX_RANGES = -1;
  private SpaceFillingCurve[] orderedSfcs;
  private ImmutableBiMap<Integer, Byte> orderedSfcIndexToTierId;
  private NumericDimensionDefinition[] baseDefinitions;
  private long maxEstimatedDuplicateIdsPerDimension;
  private final Map<Integer, BigInteger> maxEstimatedDuplicatesPerDimensionalExtent =
      new HashMap<>();

  public TieredSFCIndexStrategy() {}

  /**
   * Constructor used to create a Tiered Index Strategy.
   *
   * @param baseDefinitions the dimension definitions of the space filling curve
   * @param orderedSfcs the space filling curve used to create the strategy
   */
  public TieredSFCIndexStrategy(
      final NumericDimensionDefinition[] baseDefinitions,
      final SpaceFillingCurve[] orderedSfcs,
      final ImmutableBiMap<Integer, Byte> orderedSfcIndexToTierId) {
    this(
        baseDefinitions,
        orderedSfcs,
        orderedSfcIndexToTierId,
        DEFAULT_MAX_ESTIMATED_DUPLICATE_IDS_PER_DIMENSION);
  }

  /** Constructor used to create a Tiered Index Strategy. */
  public TieredSFCIndexStrategy(
      final NumericDimensionDefinition[] baseDefinitions,
      final SpaceFillingCurve[] orderedSfcs,
      final ImmutableBiMap<Integer, Byte> orderedSfcIndexToTierId,
      final long maxEstimatedDuplicateIdsPerDimension) {
    this.orderedSfcs = orderedSfcs;
    this.baseDefinitions = baseDefinitions;
    this.orderedSfcIndexToTierId = orderedSfcIndexToTierId;
    this.maxEstimatedDuplicateIdsPerDimension = maxEstimatedDuplicateIdsPerDimension;
    initDuplicateIdLookup();
  }

  private void initDuplicateIdLookup() {
    for (int i = 0; i <= baseDefinitions.length; i++) {
      final long maxEstimatedDuplicateIds =
          (long) Math.pow(maxEstimatedDuplicateIdsPerDimension, i);
      maxEstimatedDuplicatesPerDimensionalExtent.put(
          i,
          BigInteger.valueOf(maxEstimatedDuplicateIds));
    }
  }

  @Override
  public QueryRanges getQueryRanges(
      final MultiDimensionalNumericData indexedRange,
      final int maxRangeDecomposition,
      final IndexMetaData... hints) {
    // TODO don't just pass max ranges along to the SFC, take tiering and
    // binning into account to limit the number of ranges correctly

    final List<SinglePartitionQueryRanges> queryRanges = new ArrayList<>();
    final List<BinnedNumericDataset> binnedQueries =
        BinnedNumericDataset.applyBins(indexedRange, baseDefinitions);
    final TierIndexMetaData metaData =
        ((hints.length > 0) && (hints[0] != null) && (hints[0] instanceof TierIndexMetaData))
            ? (TierIndexMetaData) hints[0]
            : null;

    for (int sfcIndex = orderedSfcs.length - 1; sfcIndex >= 0; sfcIndex--) {
      if ((metaData != null) && (metaData.tierCounts[sfcIndex] == 0)) {
        continue;
      }
      final SpaceFillingCurve sfc = orderedSfcs[sfcIndex];
      final Byte tier = orderedSfcIndexToTierId.get(sfcIndex);
      queryRanges.addAll(
          BinnedSFCUtils.getQueryRanges(
              binnedQueries,
              sfc,
              maxRangeDecomposition, // for
              // now
              // we're
              // doing
              // this
              // per SFC/tier rather than
              // dividing by the tiers
              tier));
    }
    return new QueryRanges(queryRanges);
  }

  /**
   * Returns a list of query ranges for an specified numeric range.
   *
   * @param indexedRange defines the numeric range for the query
   * @return a List of query ranges
   */
  @Override
  public QueryRanges getQueryRanges(
      final MultiDimensionalNumericData indexedRange,
      final IndexMetaData... hints) {
    return getQueryRanges(indexedRange, DEFAULT_MAX_RANGES, hints);
  }

  /**
   * Returns a list of id's for insertion.
   *
   * @param indexedData defines the numeric data to be indexed
   * @return a List of insertion ID's
   */
  @Override
  public InsertionIds getInsertionIds(final MultiDimensionalNumericData indexedData) {
    return internalGetInsertionIds(
        indexedData,
        maxEstimatedDuplicatesPerDimensionalExtent.get(getRanges(indexedData)));
  }

  private static int getRanges(final MultiDimensionalNumericData indexedData) {
    final Double[] mins = indexedData.getMinValuesPerDimension();
    final Double[] maxes = indexedData.getMaxValuesPerDimension();
    int ranges = 0;
    for (int d = 0; d < mins.length; d++) {
      if (!FloatCompareUtils.checkDoublesEqual(mins[d], maxes[d])) {
        ranges++;
      }
    }
    return ranges;
  }

  @Override
  public InsertionIds getInsertionIds(
      final MultiDimensionalNumericData indexedData,
      final int maxDuplicateInsertionIdsPerDimension) {
    return internalGetInsertionIds(
        indexedData,
        BigInteger.valueOf(maxDuplicateInsertionIdsPerDimension));
  }

  private InsertionIds internalGetInsertionIds(
      final MultiDimensionalNumericData indexedData,
      final BigInteger maxDuplicateInsertionIds) {
    if (indexedData.isEmpty()) {
      LOGGER.warn("Cannot index empty fields, skipping writing row to index '" + getId() + "'");
      return new InsertionIds();
    }
    final List<BinnedNumericDataset> ranges =
        BinnedNumericDataset.applyBins(indexedData, baseDefinitions);
    // place each of these indices into a single row ID at a tier that will
    // fit its min and max
    final Set<SinglePartitionInsertionIds> retVal = new HashSet<>(ranges.size());
    for (final BinnedNumericDataset range : ranges) {
      retVal.add(getRowIds(range, maxDuplicateInsertionIds));
    }
    return new InsertionIds(retVal);
  }

  @Override
  public MultiDimensionalCoordinates getCoordinatesPerDimension(
      final byte[] partitionKey,
      final byte[] sortKey) {
    if ((partitionKey != null) && (partitionKey.length > 0)) {
      final byte[] rowId =
          ByteArrayUtils.combineArrays(partitionKey, sortKey == null ? null : sortKey);
      final Integer orderedSfcIndex = orderedSfcIndexToTierId.inverse().get(rowId[0]);
      return new MultiDimensionalCoordinates(
          new byte[] {rowId[0]},
          BinnedSFCUtils.getCoordinatesForId(rowId, baseDefinitions, orderedSfcs[orderedSfcIndex]));
    } else {
      LOGGER.warn("Row's partition key must at least contain a byte for the tier");
    }
    return null;
  }

  @Override
  public MultiDimensionalNumericData getRangeForId(
      final byte[] partitionKey,
      final byte[] sortKey) {
    final List<byte[]> insertionIds =
        new SinglePartitionInsertionIds(partitionKey, sortKey).getCompositeInsertionIds();
    if (insertionIds.isEmpty()) {
      LOGGER.warn("Unexpected empty insertion ID in getRangeForId()");
      return null;
    }
    final byte[] rowId = insertionIds.get(0);
    if (rowId.length > 0) {
      final Integer orderedSfcIndex = orderedSfcIndexToTierId.inverse().get(rowId[0]);
      return BinnedSFCUtils.getRangeForId(rowId, baseDefinitions, orderedSfcs[orderedSfcIndex]);
    } else {
      LOGGER.warn("Row must at least contain a byte for tier");
    }
    return null;
  }

  public void calculateCoordinateRanges(
      final List<MultiDimensionalCoordinateRanges> coordRanges,
      final BinRange[][] binRangesPerDimension,
      final IndexMetaData... hints) {
    final TierIndexMetaData metaData =
        ((hints.length > 0) && (hints[0] != null) && (hints[0] instanceof TierIndexMetaData))
            ? (TierIndexMetaData) hints[0]
            : null;

    for (int sfcIndex = orderedSfcs.length - 1; sfcIndex >= 0; sfcIndex--) {
      if ((metaData != null) && (metaData.tierCounts[sfcIndex] == 0)) {
        continue;
      }
      final SpaceFillingCurve sfc = orderedSfcs[sfcIndex];
      final Byte tier = orderedSfcIndexToTierId.get(sfcIndex);
      coordRanges.add(
          BinnedSFCUtils.getCoordinateRanges(
              binRangesPerDimension,
              sfc,
              baseDefinitions.length,
              tier));
    }
  }

  @Override
  public MultiDimensionalCoordinateRanges[] getCoordinateRangesPerDimension(
      final MultiDimensionalNumericData dataRange,
      final IndexMetaData... hints) {
    final List<MultiDimensionalCoordinateRanges> coordRanges = new ArrayList<>();
    final BinRange[][] binRangesPerDimension =
        BinnedNumericDataset.getBinnedRangesPerDimension(dataRange, baseDefinitions);
    calculateCoordinateRanges(coordRanges, binRangesPerDimension, hints);
    return coordRanges.toArray(new MultiDimensionalCoordinateRanges[] {});
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = (prime * result) + Arrays.hashCode(baseDefinitions);
    result =
        (prime * result)
            + (int) (maxEstimatedDuplicateIdsPerDimension
                ^ (maxEstimatedDuplicateIdsPerDimension >>> 32));
    result =
        (prime * result)
            + ((orderedSfcIndexToTierId == null) ? 0 : orderedSfcIndexToTierId.hashCode());
    result = (prime * result) + Arrays.hashCode(orderedSfcs);
    return result;
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (getClass() != obj.getClass()) {
      return false;
    }
    final TieredSFCIndexStrategy other = (TieredSFCIndexStrategy) obj;
    if (!Arrays.equals(baseDefinitions, other.baseDefinitions)) {
      return false;
    }
    if (maxEstimatedDuplicateIdsPerDimension != other.maxEstimatedDuplicateIdsPerDimension) {
      return false;
    }
    if (orderedSfcIndexToTierId == null) {
      if (other.orderedSfcIndexToTierId != null) {
        return false;
      }
    } else if (!orderedSfcIndexToTierId.equals(other.orderedSfcIndexToTierId)) {
      return false;
    }
    if (!Arrays.equals(orderedSfcs, other.orderedSfcs)) {
      return false;
    }
    return true;
  }

  @Override
  public String getId() {
    return StringUtils.intToString(hashCode());
  }

  @Override
  public NumericDimensionDefinition[] getOrderedDimensionDefinitions() {
    return baseDefinitions;
  }

  public boolean tierExists(final Byte tierId) {
    return orderedSfcIndexToTierId.containsValue(tierId);
  }

  private synchronized SinglePartitionInsertionIds getRowIds(
      final BinnedNumericDataset index,
      final BigInteger maxEstimatedDuplicateIds) {
    // most times this should be a single row ID, but if the lowest
    // precision tier does not have a single SFC value for this data, it
    // will be multiple row IDs

    // what tier does this entry belong in?
    for (int sfcIndex = orderedSfcs.length - 1; sfcIndex >= 0; sfcIndex--) {
      final SpaceFillingCurve sfc = orderedSfcs[sfcIndex];
      // loop through space filling curves and stop when both the min and
      // max of the ranges fit the same row ID
      final byte tierId = orderedSfcIndexToTierId.get(sfcIndex);
      final SinglePartitionInsertionIds rowIdsAtTier =
          getRowIdsAtTier(index, tierId, sfc, maxEstimatedDuplicateIds, sfcIndex);
      if (rowIdsAtTier != null) {
        return rowIdsAtTier;
      }
    }

    // this should never happen because of the check for tier 0
    return new SinglePartitionInsertionIds(null, new ArrayList<byte[]>());
  }

  public static SinglePartitionInsertionIds getRowIdsAtTier(
      final BinnedNumericDataset index,
      final Byte tierId,
      final SpaceFillingCurve sfc,
      final BigInteger maxEstimatedDuplicateIds,
      final int sfcIndex) {

    final BigInteger rowCount = sfc.getEstimatedIdCount(index);

    final SinglePartitionInsertionIds singleId =
        BinnedSFCUtils.getSingleBinnedInsertionId(rowCount, tierId, index, sfc);
    if (singleId != null) {
      return singleId;
    }

    if ((maxEstimatedDuplicateIds == null)
        || (rowCount.compareTo(maxEstimatedDuplicateIds) <= 0)
        || (sfcIndex == 0)) {
      return decomposeRangesForEntry(index, tierId, sfc);
    }
    return null;
  }

  protected static SinglePartitionInsertionIds decomposeRangesForEntry(
      final BinnedNumericDataset index,
      final Byte tierId,
      final SpaceFillingCurve sfc) {
    final List<byte[]> retVal = new ArrayList<>();
    final byte[] tierAndBinId =
        tierId != null ? ByteArrayUtils.combineArrays(new byte[] {tierId}, index.getBinId())
            : index.getBinId();
    final RangeDecomposition rangeDecomp = sfc.decomposeRange(index, false, DEFAULT_MAX_RANGES);
    // this range does not fit into a single row ID at the lowest
    // tier, decompose it
    for (final ByteArrayRange range : rangeDecomp.getRanges()) {
      ByteArrayUtils.addAllIntermediaryByteArrays(retVal, range);
    }
    return new SinglePartitionInsertionIds(tierAndBinId, retVal);
  }

  @Override
  public byte[] toBinary() {
    int byteBufferLength = (2 * orderedSfcIndexToTierId.size());
    byteBufferLength += VarintUtils.unsignedIntByteLength(orderedSfcs.length);
    final List<byte[]> orderedSfcBinaries = new ArrayList<>(orderedSfcs.length);
    byteBufferLength += VarintUtils.unsignedIntByteLength(baseDefinitions.length);
    final List<byte[]> dimensionBinaries = new ArrayList<>(baseDefinitions.length);
    byteBufferLength += VarintUtils.unsignedIntByteLength(orderedSfcIndexToTierId.size());
    byteBufferLength += VarintUtils.unsignedLongByteLength(maxEstimatedDuplicateIdsPerDimension);
    for (final SpaceFillingCurve sfc : orderedSfcs) {
      final byte[] sfcBinary = PersistenceUtils.toBinary(sfc);
      byteBufferLength += (VarintUtils.unsignedIntByteLength(sfcBinary.length) + sfcBinary.length);
      orderedSfcBinaries.add(sfcBinary);
    }
    for (final NumericDimensionDefinition dimension : baseDefinitions) {
      final byte[] dimensionBinary = PersistenceUtils.toBinary(dimension);
      byteBufferLength +=
          (VarintUtils.unsignedIntByteLength(dimensionBinary.length) + dimensionBinary.length);
      dimensionBinaries.add(dimensionBinary);
    }
    final ByteBuffer buf = ByteBuffer.allocate(byteBufferLength);
    VarintUtils.writeUnsignedInt(orderedSfcs.length, buf);
    VarintUtils.writeUnsignedInt(baseDefinitions.length, buf);
    VarintUtils.writeUnsignedInt(orderedSfcIndexToTierId.size(), buf);
    VarintUtils.writeUnsignedLong(maxEstimatedDuplicateIdsPerDimension, buf);
    for (final byte[] sfcBinary : orderedSfcBinaries) {
      VarintUtils.writeUnsignedInt(sfcBinary.length, buf);
      buf.put(sfcBinary);
    }
    for (final byte[] dimensionBinary : dimensionBinaries) {
      VarintUtils.writeUnsignedInt(dimensionBinary.length, buf);
      buf.put(dimensionBinary);
    }
    for (final Entry<Integer, Byte> entry : orderedSfcIndexToTierId.entrySet()) {
      buf.put(entry.getKey().byteValue());
      buf.put(entry.getValue());
    }

    return buf.array();
  }

  @Override
  public void fromBinary(final byte[] bytes) {
    final ByteBuffer buf = ByteBuffer.wrap(bytes);
    final int numSfcs = VarintUtils.readUnsignedInt(buf);
    final int numDimensions = VarintUtils.readUnsignedInt(buf);
    final int mappingSize = VarintUtils.readUnsignedInt(buf);
    maxEstimatedDuplicateIdsPerDimension = VarintUtils.readUnsignedLong(buf);
    orderedSfcs = new SpaceFillingCurve[numSfcs];
    baseDefinitions = new NumericDimensionDefinition[numDimensions];
    for (int i = 0; i < numSfcs; i++) {
      final byte[] sfc = ByteArrayUtils.safeRead(buf, VarintUtils.readUnsignedInt(buf));
      orderedSfcs[i] = (SpaceFillingCurve) PersistenceUtils.fromBinary(sfc);
    }
    for (int i = 0; i < numDimensions; i++) {
      final byte[] dim = ByteArrayUtils.safeRead(buf, VarintUtils.readUnsignedInt(buf));
      baseDefinitions[i] = (NumericDimensionDefinition) PersistenceUtils.fromBinary(dim);
    }
    final Builder<Integer, Byte> bimapBuilder = ImmutableBiMap.builder();
    for (int i = 0; i < mappingSize; i++) {
      bimapBuilder.put(Byte.valueOf(buf.get()).intValue(), buf.get());
    }
    orderedSfcIndexToTierId = bimapBuilder.build();

    initDuplicateIdLookup();
  }

  @Override
  public SubStrategy[] getSubStrategies() {
    final SubStrategy[] subStrategies = new SubStrategy[orderedSfcs.length];
    for (int sfcIndex = 0; sfcIndex < orderedSfcs.length; sfcIndex++) {
      final byte tierId = orderedSfcIndexToTierId.get(sfcIndex);
      subStrategies[sfcIndex] =
          new SubStrategy(
              new SingleTierSubStrategy(orderedSfcs[sfcIndex], baseDefinitions, tierId),
              new byte[] {tierId});
    }
    return subStrategies;
  }

  @Override
  public double[] getHighestPrecisionIdRangePerDimension() {
    // delegate this to the highest precision tier SFC
    return orderedSfcs[orderedSfcs.length - 1].getInsertionIdRangePerDimension();
  }

  public void setMaxEstimatedDuplicateIdsPerDimension(
      final int maxEstimatedDuplicateIdsPerDimension) {
    this.maxEstimatedDuplicateIdsPerDimension = maxEstimatedDuplicateIdsPerDimension;

    initDuplicateIdLookup();
  }

  @Override
  public int getPartitionKeyLength() {
    int rowIdOffset = 1;
    for (int dimensionIdx = 0; dimensionIdx < baseDefinitions.length; dimensionIdx++) {
      final int binSize = baseDefinitions[dimensionIdx].getFixedBinIdSize();
      if (binSize > 0) {
        rowIdOffset += binSize;
      }
    }
    return rowIdOffset;
  }

  public InsertionIds reprojectToTier(
      final byte[] insertId,
      final Byte reprojectTierId,
      final BigInteger maxDuplicates) {
    final MultiDimensionalNumericData originalRange = getRangeForId(insertId, null);
    final List<BinnedNumericDataset> ranges =
        BinnedNumericDataset.applyBins(originalRange, baseDefinitions);

    final int sfcIndex = orderedSfcIndexToTierId.inverse().get(reprojectTierId);
    final Set<SinglePartitionInsertionIds> retVal = new HashSet<>(ranges.size());
    for (final BinnedNumericDataset reprojectRange : ranges) {
      final SinglePartitionInsertionIds tierIds =
          TieredSFCIndexStrategy.getRowIdsAtTier(
              reprojectRange,
              reprojectTierId,
              orderedSfcs[sfcIndex],
              maxDuplicates,
              sfcIndex);
      retVal.add(tierIds);
    }
    return new InsertionIds(retVal);
  }

  @Override
  public List<IndexMetaData> createMetaData() {
    return Collections.singletonList(
        (IndexMetaData) new TierIndexMetaData(orderedSfcIndexToTierId.inverse()));
  }

  public static class TierIndexMetaData implements IndexMetaData {

    private int[] tierCounts = null;
    private ImmutableBiMap<Byte, Integer> orderedTierIdToSfcIndex = null;

    public TierIndexMetaData() {}

    public TierIndexMetaData(final ImmutableBiMap<Byte, Integer> orderedTierIdToSfcIndex) {
      super();
      tierCounts = new int[orderedTierIdToSfcIndex.size()];
      this.orderedTierIdToSfcIndex = orderedTierIdToSfcIndex;
    }

    @Override
    public byte[] toBinary() {
      int bufferSize = VarintUtils.unsignedIntByteLength(tierCounts.length) + tierCounts.length * 2;
      for (final int count : tierCounts) {
        bufferSize += VarintUtils.unsignedIntByteLength(count);
      }
      final ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
      VarintUtils.writeUnsignedInt(tierCounts.length, buffer);
      for (final int count : tierCounts) {
        VarintUtils.writeUnsignedInt(count, buffer);
      }
      for (final Entry<Byte, Integer> entry : orderedTierIdToSfcIndex.entrySet()) {
        buffer.put(entry.getKey().byteValue());
        buffer.put(entry.getValue().byteValue());
      }
      return buffer.array();
    }

    @Override
    public void fromBinary(final byte[] bytes) {
      final ByteBuffer buffer = ByteBuffer.wrap(bytes);
      tierCounts = new int[VarintUtils.readUnsignedInt(buffer)];
      for (int i = 0; i < tierCounts.length; i++) {
        tierCounts[i] = VarintUtils.readUnsignedInt(buffer);
      }
      final Builder<Byte, Integer> bimapBuilder = ImmutableBiMap.builder();
      for (int i = 0; i < tierCounts.length; i++) {
        bimapBuilder.put(buffer.get(), Byte.valueOf(buffer.get()).intValue());
      }
      orderedTierIdToSfcIndex = bimapBuilder.build();
    }

    @Override
    public void merge(final Mergeable merge) {
      if (merge instanceof TierIndexMetaData) {
        final TierIndexMetaData other = (TierIndexMetaData) merge;
        int pos = 0;
        for (final int count : other.tierCounts) {
          tierCounts[pos++] += count;
        }
      }
    }

    @Override
    public void insertionIdsAdded(final InsertionIds ids) {
      for (final SinglePartitionInsertionIds partitionIds : ids.getPartitionKeys()) {
        final byte first = partitionIds.getPartitionKey()[0];
        if (orderedTierIdToSfcIndex.containsKey(first)) {
          tierCounts[orderedTierIdToSfcIndex.get(first).intValue()] +=
              partitionIds.getSortKeys().size();
        }
      }
    }

    @Override
    public void insertionIdsRemoved(final InsertionIds ids) {
      for (final SinglePartitionInsertionIds partitionIds : ids.getPartitionKeys()) {
        final byte first = partitionIds.getPartitionKey()[0];
        if (orderedTierIdToSfcIndex.containsKey(first)) {
          tierCounts[orderedTierIdToSfcIndex.get(partitionIds.getPartitionKey()[0]).intValue()] -=
              partitionIds.getSortKeys().size();
        }
      }
    }

    @Override
    public String toString() {
      return "Tier Metadata[Tier Counts:" + Arrays.toString(tierCounts) + "]";
    }

    /** Convert Tiered Index Metadata statistics to a JSON object */
    @Override
    public JSONObject toJSONObject() throws JSONException {
      final JSONObject jo = new JSONObject();
      jo.put("type", "TieredSFCIndexStrategy");

      jo.put("TierCountsSize", tierCounts.length);

      if (null == orderedTierIdToSfcIndex) {
        jo.put("orderedTierIdToSfcIndex", "null");
      } else {
        jo.put("orderedTierIdToSfcIndexSize", orderedTierIdToSfcIndex.size());
      }

      return jo;
    }
  }

  @Override
  public byte[][] getInsertionPartitionKeys(final MultiDimensionalNumericData insertionData) {
    return IndexUtils.getInsertionPartitionKeys(this, insertionData);
  }

  @Override
  public byte[][] getQueryPartitionKeys(
      final MultiDimensionalNumericData queryData,
      final IndexMetaData... hints) {
    return IndexUtils.getQueryPartitionKeys(this, queryData, hints);
  }
}
