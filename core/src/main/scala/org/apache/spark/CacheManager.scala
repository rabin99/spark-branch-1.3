/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.rdd.RDD
import org.apache.spark.storage._

/* FIXME Spark类负责将RDDs分区内容传递给块管理器，并确保节点不会同时加载两个RDD副本
 * Spark class responsible for passing RDDs partition contents to the BlockManager and making
 * sure a node doesn't load two copies of an RDD at once.
 */
private[spark] class CacheManager(blockManager: BlockManager) extends Logging {

  /** Keys of RDD partitions that are being computed/loaded. */
  private val loading = new mutable.HashSet[RDDBlockId]

  /** Gets or computes an RDD partition. Used by RDD.iterator() when an RDD is cached. */
  /*
  FIXME 在RDD的iterator方法中，（查看RDD.iterator()）判断如果StorageLevel不为None，那么尝试使用CacheManager获取数据，否则重新计算，具体代码见：RDD.iterator
   */
  def getOrCompute[T](
      rdd: RDD[T],
      partition: Partition,
      context: TaskContext,
      storageLevel: StorageLevel): Iterator[T] = {

    val key = RDDBlockId(rdd.id, partition.index)
    logDebug(s"Looking for partition $key")

    // FIXME 直接用BlockManager来获取数据，如果获取到了，直接返回
    blockManager.get(key) match {

      case Some(blockResult) =>
        // Partition is already materialized, so just return its values
        val inputMetrics = blockResult.inputMetrics
        val existingMetrics = context.taskMetrics
          .getInputMetricsForReadMethod(inputMetrics.readMethod)
        existingMetrics.incBytesRead(inputMetrics.bytesRead)

        val iter = blockResult.data.asInstanceOf[Iterator[T]]
        new InterruptibleIterator[T](context, iter) {
          override def next(): T = {
            existingMetrics.incRecordsRead(1)
            delegate.next()
          }
        }
        // FIXME 如果BlockManager没有获取到数据，虽然rdd持久化过，但是未知原因，数据既不在本地内存、磁盘也不在远程BlockManager的本地磁盘，此时就要后续的处理。
      case None =>
        // Acquire a lock for loading this partition
        // If another thread already holds the lock, wait for it to finish return its results
        // FIXME 再调用一个BlockManager的get方法获取数据，如果有返回，没有往后走
        val storedValues = acquireLockForPartition[T](key)
        if (storedValues.isDefined) {
          return new InterruptibleIterator[T](context, storedValues.get)
        }

        // Otherwise, we have to load the partition ourselves
        try {
          logInfo(s"Partition $key not found, computing it")
          // FIXME 调用computeOrReadCheckpoint，如果rdd checkpoint过，那么读取它的checkpoint，否则，重新使用父rdd，执行算子计算一份
          val computedValues = rdd.computeOrReadCheckpoint(partition, context)

          // If the task is running locally, do not persist the result
          // FIXME 如果数据是在Driver的本地计算，那么直接返回，否则，还需要对数据进行持久化操作。
          if (context.isRunningLocally) {
            return computedValues
          }

          // Otherwise, cache the values and keep track of any updates in block statuses
          // FIXME 由于走CacheManager意味着rdd是设置过持久化级别的，只是因为某些原因，持久化的数据没有找到，那么才会走到这里
          // FIXME 所以读取了checkpoint数据，或者重新计算数据之后，要用putInBlockManager，将数据在BlockManager中持久化一份
          val updatedBlocks = new ArrayBuffer[(BlockId, BlockStatus)]
          val cachedValues = putInBlockManager(key, computedValues, storageLevel, updatedBlocks)
          val metrics = context.taskMetrics
          val lastUpdatedBlocks = metrics.updatedBlocks.getOrElse(Seq[(BlockId, BlockStatus)]())
          metrics.updatedBlocks = Some(lastUpdatedBlocks ++ updatedBlocks.toSeq)
          new InterruptibleIterator(context, cachedValues)

        } finally {
          loading.synchronized {
            loading.remove(key)
            loading.notifyAll()
          }
        }
    }
  }

  /**
   * Acquire a loading lock for the partition identified by the given block ID.
   *
   * If the lock is free, just acquire it and return None. Otherwise, another thread is already
   * loading the partition, so we wait for it to finish and return the values loaded by the thread.
   */
  /*
  获取由给定RDDBlockId的分区的加载锁。
  如果锁是免费的，那么只需要获取它，不返回任何锁。
  否则，另一个线程已经在加载分区，因此我们等待它完成并返回线程加载的值。
   */
  private def acquireLockForPartition[T](id: RDDBlockId): Option[Iterator[T]] = {
    loading.synchronized {
      if (!loading.contains(id)) {
        // If the partition is free, acquire its lock to compute its value
        loading.add(id)
        None
      } else {
        // Otherwise, wait for another thread to finish and return its result
        logInfo(s"Another thread is loading $id, waiting for it to finish...")
        while (loading.contains(id)) {
          try {
            loading.wait()
          } catch {
            case e: Exception =>
              logWarning(s"Exception while waiting for another thread to load $id", e)
          }
        }
        logInfo(s"Finished waiting for $id")
        val values = blockManager.get(id)
        if (!values.isDefined) {
          /* The block is not guaranteed to exist even after the other thread has finished.
           * For instance, the block could be evicted after it was put, but before our get.
           * In this case, we still need to load the partition ourselves. */
          logInfo(s"Whoever was loading $id failed; we'll try it ourselves")
          loading.add(id)
        }
        values.map(_.data.asInstanceOf[Iterator[T]])
      }
    }
  }

  /*
   * Cache the values of a partition, keeping track of any updates in the storage statuses of
   * other blocks along the way.
   *
   * The effective storage level refers to the level that actually specifies BlockManager put
   * behavior, not the level originally specified by the user. This is mainly for forcing a
   * MEMORY_AND_DISK partition to disk if there is not enough room to unroll the partition,
   * while preserving the the original semantics of the RDD as specified by the application.
   */
  /*
  缓存分区的值，跟踪在此过程中其他块的存储状态中的任何更新。
  有效存储级别是指实际指定BlockManager put行为的级别，而不是用户最初指定的级别。
  这主要用于在没有足够空间展开分区的情况下将MEMORY_AND_DISK分区强制转换为磁盘，同时保留应用程序指定的RDD的原始语义。
   */
  private def putInBlockManager[T](
      key: BlockId,
      values: Iterator[T],
      level: StorageLevel,
      updatedBlocks: ArrayBuffer[(BlockId, BlockStatus)],
      effectiveStorageLevel: Option[StorageLevel] = None): Iterator[T] = {

    val putLevel = effectiveStorageLevel.getOrElse(level)
    // FIXME 如果持久化级别，没有指定内存，仅仅是纯磁盘的级别
    if (!putLevel.useMemory) {
      /*
       * This RDD is not to be cached in memory, so we can just pass the computed values as an
       * iterator directly to the BlockManager rather than first fully unrolling it in memory.
       */
      // FIXME 那么直接调用BlockManager的putIterator()方法，将数据写入磁盘即可
      updatedBlocks ++=
        blockManager.putIterator(key, values, level, tellMaster = true, effectiveStorageLevel)
      blockManager.get(key) match {
        case Some(v) => v.data.asInstanceOf[Iterator[T]]
        case None =>
          logInfo(s"Failure to store $key")
          throw new BlockException(key, s"Block manager failed to return cached value for $key!")
      }
    } else {
      /*
       * This RDD is to be cached in memory. In this case we cannot pass the computed values
       * to the BlockManager as an iterator and expect to read it back later. This is because
       * we may end up dropping a partition from memory store before getting it back.
       *
       * In addition, we must be careful to not unroll the entire partition in memory at once.
       * Otherwise, we may cause an OOM exception if the JVM does not have enough space for this
       * single partition. Instead, we unroll the values cautiously, potentially aborting and
       * dropping the partition to disk if applicable.
       */
      // FIXME 如果unrollSafely方法判断数据可以写入内存，就写入，如果无法写入内存，那么只能写入磁盘文件
      blockManager.memoryStore.unrollSafely(key, values, updatedBlocks) match {
        case Left(arr) =>
          // We have successfully unrolled the entire partition, so cache it in memory
          updatedBlocks ++=
            blockManager.putArray(key, arr, level, tellMaster = true, effectiveStorageLevel)
          arr.iterator.asInstanceOf[Iterator[T]]
        case Right(it) =>
          // There is not enough space to cache this partition in memory
          val returnValues = it.asInstanceOf[Iterator[T]]
          // FIXME 如果有些数据失真无法写入内存，那么就判断，数据是否有磁盘级别
          // FIXME 如果有的话，那么就用磁盘级别，将数据写入磁盘
          if (putLevel.useDisk) {
            logWarning(s"Persisting partition $key to disk instead.")
            val diskOnlyLevel = StorageLevel(useDisk = true, useMemory = false,
              useOffHeap = false, deserialized = false, putLevel.replication)
            putInBlockManager[T](key, returnValues, level, updatedBlocks, Some(diskOnlyLevel))
          } else {
            returnValues
          }
      }
    }
  }

}
