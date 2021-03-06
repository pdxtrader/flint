/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries

import com.twosigma.flint.rdd.{ KeyPartitioningType, OrderedRDD, RangeSplit }
import com.twosigma.flint.timeseries.time.types.TimeType
import org.apache.spark.{ Dependency, OneToOneDependency }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{ Ascending, AttributeReference, SortOrder }
import org.apache.spark.sql.catalyst.plans.physical.{ ClusteredDistribution, OrderedDistribution }
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.types.{ LongType, StructType, TimestampType }
import org.apache.spark.storage.StorageLevel

/**
 * - a DataFrame will be normalized via DataFrame -> (normalized ) OrderedRDD -> DataFrame
 * conversion if there is no partition information provided;
 * - a DataFrame will be used directly as a backend if a partition information is provided.
 */
private[timeseries] object TimeSeriesStore {

  /**
   * Convert a [[org.apache.spark.sql.DataFrame]] to a [[TimeSeriesStore]].
   *
   * @param dataFrame   A [[org.apache.spark.sql.DataFrame]] with `time` column, and sorted by time value.
   * @param partInfoOpt This parameter should be either empty, or correctly represent partitioning of
   *                    the given DataFrame.
   * @return a [[TimeSeriesStore]].
   */
  def apply(dataFrame: DataFrame, partInfoOpt: Option[PartitionInfo]): TimeSeriesStore = partInfoOpt match {
    case Some(partInfo) =>
      new NormalizedDataFrameStore(dataFrame, partInfo)
    case None =>
      val schema = dataFrame.schema
      val internalRows = dataFrame.queryExecution.toRdd
      val pairRdd = internalRows.mapPartitions { rows =>
        val converter = TimeSeriesStore.getInternalRowConverter(schema, requireCopy = false)
        rows.map(converter)
      }

      // Ideally, we may use dataFrame.select("time").queryExecution.toRdd to build the `keyRdd`.
      // This may allow us push down column pruning and thus reduce IO. However, there is no guarantee that
      // DataFrame.sort("time").select("time") preserves partitioning as DataFrame.sort("time").
      // val keyRdd = internalRows.mapPartitions { rows => rows.map(_.getLong(timeColumnIndex)) }
      val keyRdd = pairRdd.mapPartitions { tuples => tuples.map(_._1) }

      // The input DataFrame is sorted already, along with clustered distribution it's normalized
      val isNormalized = isClustered(dataFrame.queryExecution.executedPlan)

      val orderedRdd = OrderedRDD.fromRDD(
        pairRdd,
        KeyPartitioningType(isSorted = true, isNormalized = isNormalized),
        keyRdd
      )
      TimeSeriesStore(orderedRdd, schema)
  }

  /**
   * Convert an [[OrderedRDD]] to a [[TimeSeriesStore]].
   *
   * @param orderedRdd  An [[OrderedRDD]] with `time` column, and sorted by time value.
   * @param schema      Schema of this [[TimeSeriesStore]].
   * @return a [[TimeSeriesStore]].
   */
  def apply(orderedRdd: OrderedRDD[Long, InternalRow], schema: StructType): TimeSeriesStore = {
    val df = DFConverter.toDataFrame(orderedRdd, schema)

    require(
      orderedRdd.getNumPartitions == 0 || orderedRdd.partitions(0).index == 0,
      "Partition index should start with zero."
    )
    // the rdd parameter is not used
    val deps = new OneToOneDependency(null)
    val partInfo = PartitionInfo(orderedRdd.rangeSplits, Seq(deps))

    new NormalizedDataFrameStore(df, partInfo)
  }

  /**
   * Similar to TimeSeriesRDD.getRowConverter(), but used to convert a [[DataFrame]] into a [[TimeSeriesRDD]]
   *
   * @param schema          The schema of the input rows.
   * @param requireCopy     Whether to require new row objects or reuse the existing ones.
   * @return                a function to convert [[InternalRow]] into a tuple.
   * @note                  if `requireNewCopy` is true then the function makes an extra copy of the row value.
   *                        Otherwise it makes no copies of the row.
   */
  private[timeseries] def getInternalRowConverter(
    schema: StructType,
    requireCopy: Boolean
  ): InternalRow => (Long, InternalRow) = {
    val timeColumnIndex = schema.fieldIndex(TimeSeriesRDD.timeColumnName)
    val timeType = TimeType(schema(timeColumnIndex).dataType)

    if (requireCopy) {
      (row: InternalRow) => (timeType.internalToNanos(row.getLong(timeColumnIndex)), row.copy())
    } else {
      (row: InternalRow) => (timeType.internalToNanos(row.getLong(timeColumnIndex)), row)
    }
  }

  private def getTimeAttribute(executedPlan: SparkPlan): AttributeReference = {
    val timeAttributes = executedPlan.output.collect {
      case reference: AttributeReference => reference
    }.filter(_.name == TimeSeriesRDD.timeColumnName)

    require(timeAttributes.size == 1, s"Time attribute is not unique. $timeAttributes")

    return timeAttributes.head
  }

  /**
   * Check whether output distribution says the DataFrame is sorted
   * @param executedPlan
   * @return
   */
  private[timeseries] def isSorted(executedPlan: SparkPlan): Boolean = {
    val timeAttribute = getTimeAttribute(executedPlan)
    val requiredDistribution = OrderedDistribution(Seq(SortOrder(timeAttribute, Ascending)))
    executedPlan.outputPartitioning.satisfies(requiredDistribution)
  }

  /**
   * Check whether output distribution says the DataFrame is clustered.
   *
   * Note: This doesn't not check the ordering of time column
   *
   * @param executedPlan  a given [[SparkPlan]]
   * @return true if a data frame is already clustered, and false otherwise.
   */
  private[timeseries] def isClustered(executedPlan: SparkPlan): Boolean = {
    val timeAttribute = getTimeAttribute(executedPlan)
    val requiredDistribution = ClusteredDistribution(Seq(timeAttribute))
    executedPlan.outputPartitioning.satisfies(requiredDistribution)
  }
}

private[timeseries] sealed trait TimeSeriesStore extends Serializable {
  /**
   * Returns the schema of this [[TimeSeriesStore]].
   */
  def schema: StructType

  /**
   * Returns an [[RDD]] representation of this [[TimeSeriesStore]].
   */
  def rdd: RDD[Row]

  /**
   * Returns an [[OrderedRDD]] representation of this [[TimeSeriesStore]] with safe row copies.
   */
  def orderedRdd: OrderedRDD[Long, InternalRow]

  /**
   * Returns an [[OrderedRDD]] representation of this [[TimeSeriesStore]] with `unsafe` rows.
   */
  def unsafeOrderedRdd: OrderedRDD[Long, InternalRow]

  /**
   * Returns [[PartitionInfo]] if internal representation is normalized, or None otherwise.
   */
  def partInfo: Option[PartitionInfo]

  /**
   * Returns a [[org.apache.spark.sql.DataFrame]] representation of this [[TimeSeriesStore]].
   */
  def dataFrame: DataFrame

  /**
   * Persists this [[TimeSeriesStore]] with default storage level (MEMORY_ONLY).
   */
  def cache(): Unit

  /**
   * Persists this [[TimeSeriesStore]] with default storage level (MEMORY_ONLY).
   */
  def persist(): Unit

  /**
   * Persists this [[TimeSeriesStore]] with specified storage level.
   *
   * @param newLevel The storage level.
   */
  def persist(newLevel: StorageLevel): Unit

  /**
   * Marks this [[TimeSeriesStore]] as non-persistent, and remove all blocks for it from memory and disk.
   *
   * @param blocking Whether to block until all blocks are deleted.
   */
  def unpersist(blocking: Boolean = true): Unit
}

/**
 * There are two ways to retrieve a DataFrame object in this class:
 * - use internalDf object (should be used for all DataFrame operations)
 * - use newDf function (should be used if you access lazy fields of DF's QueryExecution)
 */
private[timeseries] class NormalizedDataFrameStore(
  private val internalDf: DataFrame,
  private var internalPartInfo: PartitionInfo
) extends TimeSeriesStore {
  require(internalPartInfo.deps.size == 1)
  require(internalPartInfo.deps.head.isInstanceOf[OneToOneDependency[_]])
  require(
    PartitionPreservingOperation.isPartitionPreservingDataFrame(internalDf),
    s"df is not a PartitionPreservingRDDScanDataFrame. " +
      s"sparkPlan: ${PartitionPreservingOperation.executedPlan(internalDf)}"
  )

  override val schema: StructType = internalDf.schema

  override def rdd: RDD[Row] = newDf.rdd

  /**
   * The field below can be used only for DataFrame operations, since they respect DF caching.
   * If you need an [[RDD]] representation - use `newDF`.
   */
  override val dataFrame: DataFrame = internalDf

  override def orderedRdd: OrderedRDD[Long, InternalRow] = toOrderedRdd(requireCopy = true)

  override def unsafeOrderedRdd: OrderedRDD[Long, InternalRow] = toOrderedRdd(requireCopy = false)

  override def partInfo: Option[PartitionInfo] = Some(internalPartInfo)

  override def persist(): Unit = internalDf.persist()

  override def persist(newLevel: StorageLevel): Unit = internalDf.persist(newLevel)

  override def cache(): Unit = internalDf.cache()

  override def unpersist(blocking: Boolean): Unit = internalDf.unpersist(blocking)

  private def toOrderedRdd(requireCopy: Boolean): OrderedRDD[Long, InternalRow] = {
    val internalRows = newDf.queryExecution.toRdd
    val pairRdd = internalRows.mapPartitions { rows =>
      val converter = TimeSeriesStore.getInternalRowConverter(schema, requireCopy)
      rows.map(converter)
    }

    OrderedRDD.fromRDD(pairRdd, internalPartInfo.deps, internalPartInfo.splits)
  }

  /**
   * We create a new DataFrame object to force reevaluation of 'lazy val' fields
   * in [[org.apache.spark.sql.execution.QueryExecution]].
   */
  private def newDf: DataFrame = DFConverter.newDataFrame(internalDf)
}

private[timeseries] case class PartitionInfo(
  splits: Seq[RangeSplit[Long]],
  deps: Seq[Dependency[_]]
) extends Serializable {
  // TODO: Add checks to make sure partition boundary is at least second resolution here.
  //       This is currently not done because Smooth even size partitioning strategy will
  //       give nanoseconds resolution.
}
