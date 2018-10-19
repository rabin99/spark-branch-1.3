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

package org.apache.spark.storage

import java.util.{HashMap => JHashMap}

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask

import org.apache.spark.{Logging, SparkConf, SparkException}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.scheduler._
import org.apache.spark.storage.BlockManagerMessages._
import org.apache.spark.util.{ActorLogReceive, AkkaUtils, Utils}

/* FIXME BlockManagerMasterActor是主节点上的一个参与者，用于跟踪所有从服务器的块管理器的状态。
 * FIXME BlockManagerMasterActor负责维护各个executor的BlockManager的元数据BlockManagerInfo、BlockStatus
 * BlockManagerMasterActor is an actor on the master node to track statuses of
 * all slaves' block managers.
 */
private[spark]
class BlockManagerMasterActor(val isLocal: Boolean, conf: SparkConf, listenerBus: LiveListenerBus)
  extends Actor with ActorLogReceive with Logging {

  // Mapping from block manager id to the block manager's information.
  // FIXME Block Manager ID到BlockManagerInfo 的映射
  // FIXME BlockManagerMaster要负责维护每个BlockManager的BlockManagerInfo
  private val blockManagerInfo = new mutable.HashMap[BlockManagerId, BlockManagerInfo]

  // Mapping from executor ID to block manager ID.
  // FIXME executorID 到 Block Manager ID 的映射，executor是与BlockManager相关联
  private val blockManagerIdByExecutor = new mutable.HashMap[String, BlockManagerId]

  // Mapping from block id to the set of block managers that have the block.
  private val blockLocations = new JHashMap[BlockId, mutable.HashSet[BlockManagerId]]

  private val akkaTimeout = AkkaUtils.askTimeout(conf)

  override def receiveWithLogging = {
    case RegisterBlockManager(blockManagerId, maxMemSize, slaveActor) =>
      register(blockManagerId, maxMemSize, slaveActor)
      sender ! true

    case UpdateBlockInfo(
      blockManagerId, blockId, storageLevel, deserializedSize, size, tachyonSize) =>
      sender ! updateBlockInfo(
        blockManagerId, blockId, storageLevel, deserializedSize, size, tachyonSize)

    case GetLocations(blockId) =>
      sender ! getLocations(blockId)

    case GetLocationsMultipleBlockIds(blockIds) =>
      sender ! getLocationsMultipleBlockIds(blockIds)

    case GetPeers(blockManagerId) =>
      sender ! getPeers(blockManagerId)

    case GetActorSystemHostPortForExecutor(executorId) =>
      sender ! getActorSystemHostPortForExecutor(executorId)

    case GetMemoryStatus =>
      sender ! memoryStatus

    case GetStorageStatus =>
      sender ! storageStatus

    case GetBlockStatus(blockId, askSlaves) =>
      sender ! blockStatus(blockId, askSlaves)

    case GetMatchingBlockIds(filter, askSlaves) =>
      sender ! getMatchingBlockIds(filter, askSlaves)

    case RemoveRdd(rddId) =>
      sender ! removeRdd(rddId)

    case RemoveShuffle(shuffleId) =>
      sender ! removeShuffle(shuffleId)

    case RemoveBroadcast(broadcastId, removeFromDriver) =>
      sender ! removeBroadcast(broadcastId, removeFromDriver)

    case RemoveBlock(blockId) =>
      removeBlockFromWorkers(blockId)
      sender ! true

    case RemoveExecutor(execId) =>
      removeExecutor(execId)
      sender ! true

    case StopBlockManagerMaster =>
      sender ! true
      context.stop(self)

    case BlockManagerHeartbeat(blockManagerId) =>
      sender ! heartbeatReceived(blockManagerId)

    case other =>
      logWarning("Got unknown message: " + other)
  }

  private def removeRdd(rddId: Int): Future[Seq[Int]] = {
    // First remove the metadata for the given RDD, and then asynchronously remove the blocks
    // from the slaves.

    // Find all blocks for the given RDD, remove the block from both blockLocations and
    // the blockManagerInfo that is tracking the blocks.
    val blocks = blockLocations.keys.flatMap(_.asRDDId).filter(_.rddId == rddId)
    blocks.foreach { blockId =>
      val bms: mutable.HashSet[BlockManagerId] = blockLocations.get(blockId)
      bms.foreach(bm => blockManagerInfo.get(bm).foreach(_.removeBlock(blockId)))
      blockLocations.remove(blockId)
    }

    // Ask the slaves to remove the RDD, and put the result in a sequence of Futures.
    // The dispatcher is used as an implicit argument into the Future sequence construction.
    import context.dispatcher
    val removeMsg = RemoveRdd(rddId)
    Future.sequence(
      blockManagerInfo.values.map { bm =>
        bm.slaveActor.ask(removeMsg)(akkaTimeout).mapTo[Int]
      }.toSeq
    )
  }

  private def removeShuffle(shuffleId: Int): Future[Seq[Boolean]] = {
    // Nothing to do in the BlockManagerMasterActor data structures
    import context.dispatcher
    val removeMsg = RemoveShuffle(shuffleId)
    Future.sequence(
      blockManagerInfo.values.map { bm =>
        bm.slaveActor.ask(removeMsg)(akkaTimeout).mapTo[Boolean]
      }.toSeq
    )
  }

  /**
   * Delegate RemoveBroadcast messages to each BlockManager because the master may not notified
   * of all broadcast blocks. If removeFromDriver is false, broadcast blocks are only removed
   * from the executors, but not from the driver.
   */
  private def removeBroadcast(broadcastId: Long, removeFromDriver: Boolean): Future[Seq[Int]] = {
    import context.dispatcher
    val removeMsg = RemoveBroadcast(broadcastId, removeFromDriver)
    val requiredBlockManagers = blockManagerInfo.values.filter { info =>
      removeFromDriver || !info.blockManagerId.isDriver
    }
    Future.sequence(
      requiredBlockManagers.map { bm =>
        bm.slaveActor.ask(removeMsg)(akkaTimeout).mapTo[Int]
      }.toSeq
    )
  }

  // FIXME 删除BlockManager方法
  private def removeBlockManager(blockManagerId: BlockManagerId) {
    // FIXME 尝试根据blockManagerId获取它对应的BlockManagerInfo
    val info = blockManagerInfo(blockManagerId)

    // Remove the block manager from blockManagerIdByExecutor.
    // fixme 从blockManagerIdByExecutor map中移除executorId对应的BlockManager
    blockManagerIdByExecutor -= blockManagerId.executorId

    // Remove it from blockManagerInfo and remove all the blocks.
    // fixme 从blockManagerInfo map中也相应移除blockManagerInfo
    blockManagerInfo.remove(blockManagerId)
    val iterator = info.blocks.keySet.iterator

    // fixme 遍历BlockManagerInfo内部所有的block块的blockId
    while (iterator.hasNext) {
      val blockId = iterator.next
      val locations = blockLocations.get(blockId)
      // fixme 清空BlockManagerInfo内部的block的BlockStatus信息
      locations -= blockManagerId
      if (locations.size == 0) {
        blockLocations.remove(blockId)
      }
    }
    listenerBus.post(SparkListenerBlockManagerRemoved(System.currentTimeMillis(), blockManagerId))
    logInfo(s"Removing block manager $blockManagerId")
  }

  private def removeExecutor(execId: String) {
    logInfo("Trying to remove executor " + execId + " from BlockManagerMaster.")
    //  FIXME removeBlockManager获取executorId对应的BlockmanagerInfo,对其调用removeBlockManager()方法
    blockManagerIdByExecutor.get(execId).foreach(removeBlockManager)
  }

  /**
   * Return true if the driver knows about the given block manager. Otherwise, return false,
   * indicating that the block manager should re-register.
   */
  private def heartbeatReceived(blockManagerId: BlockManagerId): Boolean = {
    if (!blockManagerInfo.contains(blockManagerId)) {
      blockManagerId.isDriver && !isLocal
    } else {
      blockManagerInfo(blockManagerId).updateLastSeenMs()
      true
    }
  }

  // Remove a block from the slaves that have it. This can only be used to remove
  // blocks that the master knows about.
  private def removeBlockFromWorkers(blockId: BlockId) {
    val locations = blockLocations.get(blockId)
    if (locations != null) {
      locations.foreach { blockManagerId: BlockManagerId =>
        val blockManager = blockManagerInfo.get(blockManagerId)
        if (blockManager.isDefined) {
          // Remove the block from the slave's BlockManager.
          // Doesn't actually wait for a confirmation and the message might get lost.
          // If message loss becomes frequent, we should add retry logic here.
          blockManager.get.slaveActor.ask(RemoveBlock(blockId))(akkaTimeout)
        }
      }
    }
  }

  // Return a map from the block manager id to max memory and remaining memory.
  private def memoryStatus: Map[BlockManagerId, (Long, Long)] = {
    blockManagerInfo.map { case(blockManagerId, info) =>
      (blockManagerId, (info.maxMem, info.remainingMem))
    }.toMap
  }

  private def storageStatus: Array[StorageStatus] = {
    blockManagerInfo.map { case (blockManagerId, info) =>
      new StorageStatus(blockManagerId, info.maxMem, info.blocks)
    }.toArray
  }

  /**
   * Return the block's status for all block managers, if any. NOTE: This is a
   * potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, the master queries each block manager for the most updated block
   * statuses. This is useful when the master is not informed of the given block by all block
   * managers.
   */
  private def blockStatus(
      blockId: BlockId,
      askSlaves: Boolean): Map[BlockManagerId, Future[Option[BlockStatus]]] = {
    import context.dispatcher
    val getBlockStatus = GetBlockStatus(blockId)
    /*
     * Rather than blocking on the block status query, master actor should simply return
     * Futures to avoid potential deadlocks. This can arise if there exists a block manager
     * that is also waiting for this master actor's response to a previous message.
     */
    blockManagerInfo.values.map { info =>
      val blockStatusFuture =
        if (askSlaves) {
          info.slaveActor.ask(getBlockStatus)(akkaTimeout).mapTo[Option[BlockStatus]]
        } else {
          Future { info.getStatus(blockId) }
        }
      (info.blockManagerId, blockStatusFuture)
    }.toMap
  }

  /**
   * Return the ids of blocks present in all the block managers that match the given filter.
   * NOTE: This is a potentially expensive operation and should only be used for testing.
   *
   * If askSlaves is true, the master queries each block manager for the most updated block
   * statuses. This is useful when the master is not informed of the given block by all block
   * managers.
   */
  private def getMatchingBlockIds(
      filter: BlockId => Boolean,
      askSlaves: Boolean): Future[Seq[BlockId]] = {
    import context.dispatcher
    val getMatchingBlockIds = GetMatchingBlockIds(filter)
    Future.sequence(
      blockManagerInfo.values.map { info =>
        val future =
          if (askSlaves) {
            info.slaveActor.ask(getMatchingBlockIds)(akkaTimeout).mapTo[Seq[BlockId]]
          } else {
            Future { info.blocks.keys.filter(filter).toSeq }
          }
        future
      }
    ).map(_.flatten.toSeq)
  }

  /*
  FIXME 注册BlockManager
   */
  private def register(id: BlockManagerId, maxMemSize: Long, slaveActor: ActorRef) {
    val time = System.currentTimeMillis()

    // FIXME 首先判断，如果本地HashMap中没有指定的BlockManagerId,说明没有注册，那么才会执行注册逻辑
    if (!blockManagerInfo.contains(id)) {
      // FIXME 根据blockManager对应的executorID找到对应的BlockManagerInfo
      // FIXME 这里其实是做一个安全判断，因为，如果blockManagerInfo map里没有BlockManagerId，
      // FIXME 那么同步的blockManagerIdByExecutor map里，也必须没有；如果有，那么需要先清理掉。
      blockManagerIdByExecutor.get(id.executorId) match {
        case Some(oldId) =>
          // A block manager of the same executor already exists, so remove it (assumed dead)
          logError("Got two different block manager registrations on same executor - " 
              + s" will replace old one $oldId with new one $id")
          // FIXME 移除executorId相关的blockManagerInfo
          removeExecutor(id.executorId)  
        case None =>
      }
      logInfo("Registering block manager %s with %s RAM, %s".format(
        id.hostPort, Utils.bytesToString(maxMemSize), id))

      // scala中HashMap赋值操作后，就直接添加了一个元素进去。
      // fixme executorId到blockmanagerId
      blockManagerIdByExecutor(id.executorId) = id
      // fixme 为blockManagerId创建一份BlockManagerInfo
      blockManagerInfo(id) = new BlockManagerInfo(
        id, System.currentTimeMillis(), maxMemSize, slaveActor)
    }
    listenerBus.post(SparkListenerBlockManagerAdded(time, id, maxMemSize))
  }

  /*
  FIXME 更新blockInfo，每个BlockManager上，如果block发生变化，都要发送updateBlockInfo请求到BlockManagerMaster
   */
  private def updateBlockInfo(
      blockManagerId: BlockManagerId,
      blockId: BlockId,
      storageLevel: StorageLevel,
      memSize: Long,
      diskSize: Long,
      tachyonSize: Long): Boolean = {

    if (!blockManagerInfo.contains(blockManagerId)) {
      if (blockManagerId.isDriver && !isLocal) {
        // We intentionally do not register the master (except in local mode),
        // so we should not indicate failure.
        return true
      } else {
        return false
      }
    }

    if (blockId == null) {
      blockManagerInfo(blockManagerId).updateLastSeenMs()
      return true
    }

    // FIXME 调用blockManager的BlockManagerInfo的updateBlockInfo()，更新block信息
    blockManagerInfo(blockManagerId).updateBlockInfo(
      blockId, storageLevel, memSize, diskSize, tachyonSize)

    // FIXME 每个block可能会在多个BlockManager上，如果将StorageLevel设置成带_2的这种，
    // FIXME 那么就需要将block replicate一份到其他BlockManager上
    // FIXME blockLocations Map 其实就是保存了每个blockId对应的BlockManagerId的set集合
    // FIXME 所有这里，会更新blockLocation中的信息，因为使用set存储BlockManagerId,因为自动就去重了。
    var locations: mutable.HashSet[BlockManagerId] = null
    if (blockLocations.containsKey(blockId)) {
      locations = blockLocations.get(blockId)
    } else {
      locations = new mutable.HashSet[BlockManagerId]
      blockLocations.put(blockId, locations)
    }

    if (storageLevel.isValid) {
      locations.add(blockManagerId)
    } else {
      locations.remove(blockManagerId)
    }

    // Remove the block from master tracking if it has been removed on all slaves.
    if (locations.size == 0) {
      blockLocations.remove(blockId)
    }
    true
  }

  private def getLocations(blockId: BlockId): Seq[BlockManagerId] = {
    if (blockLocations.containsKey(blockId)) blockLocations.get(blockId).toSeq else Seq.empty
  }

  private def getLocationsMultipleBlockIds(blockIds: Array[BlockId]): Seq[Seq[BlockManagerId]] = {
    blockIds.map(blockId => getLocations(blockId))
  }

  /** Get the list of the peers of the given block manager */
  private def getPeers(blockManagerId: BlockManagerId): Seq[BlockManagerId] = {
    val blockManagerIds = blockManagerInfo.keySet
    if (blockManagerIds.contains(blockManagerId)) {
      blockManagerIds.filterNot { _.isDriver }.filterNot { _ == blockManagerId }.toSeq
    } else {
      Seq.empty
    }
  }

  /**
   * Returns the hostname and port of an executor's actor system, based on the Akka address of its
   * BlockManagerSlaveActor.
   */
  private def getActorSystemHostPortForExecutor(executorId: String): Option[(String, Int)] = {
    for (
      blockManagerId <- blockManagerIdByExecutor.get(executorId);
      info <- blockManagerInfo.get(blockManagerId);
      host <- info.slaveActor.path.address.host;
      port <- info.slaveActor.path.address.port
    ) yield {
      (host, port)
    }
  }
}

@DeveloperApi
case class BlockStatus(
    storageLevel: StorageLevel,
    memSize: Long,
    diskSize: Long,
    tachyonSize: Long) {
  def isCached: Boolean = memSize + diskSize + tachyonSize > 0
}

@DeveloperApi
object BlockStatus {
  def empty: BlockStatus = BlockStatus(StorageLevel.NONE, 0L, 0L, 0L)
}

// FIXME BlockManagerInfo相当于是BlockManager的元数据
private[spark] class BlockManagerInfo(
    val blockManagerId: BlockManagerId,
    timeMs: Long,
    val maxMem: Long,
    val slaveActor: ActorRef)
  extends Logging {

  private var _lastSeenMs: Long = timeMs
  private var _remainingMem: Long = maxMem

  // Mapping from block id to its status.
  // FIXME BlockManagerInfo管理着每个BlockManager内部的block的blockId-BlockStatus的映射
  private val _blocks = new JHashMap[BlockId, BlockStatus]

  def getStatus(blockId: BlockId) = Option(_blocks.get(blockId))

  def updateLastSeenMs() {
    _lastSeenMs = System.currentTimeMillis()
  }

  def updateBlockInfo(
      blockId: BlockId,
      storageLevel: StorageLevel,
      memSize: Long,
      diskSize: Long,
      tachyonSize: Long) {  // fixme 早期版本还支持tachyonsize

    updateLastSeenMs()

    // FIXME 判断内部有这个block
    if (_blocks.containsKey(blockId)) {
      // The block exists on the slave already.
      val blockStatus: BlockStatus = _blocks.get(blockId)
      val originalLevel: StorageLevel = blockStatus.storageLevel
      val originalMemSize: Long = blockStatus.memSize

      // FIXME 判断如果StorageLevel是使用内存，那么就给剩余内存数量加上当前的内存量
      if (originalLevel.useMemory) {
        _remainingMem += originalMemSize
      }
    }

    // FIXME 给block创建一份BlockStatus，然后根据其持久化级别，对相应的内存资源进行计算
    if (storageLevel.isValid) {
      /*
      isValid意味着它要么存储在内存中，要么存储在磁盘上，要么存储Tachyon上。
      这里的memSize表示在内存中或从内存中删除的数据大小，
      这里的tachyonSize表示在速子中或从速子中删除的数据大小，
      这里的diskSize表示在磁盘中或从磁盘中删除的数据大小。
      当一个块从内存中删除到磁盘时，它们都可以大于0。
      因此，一个安全的设置BlockStatus的方法是在精确的模式下设置它的信息。
       */
      /* isValid means it is either stored in-memory, on-disk or on-Tachyon.
       * The memSize here indicates the data size in or dropped from memory,
       * tachyonSize here indicates the data size in or dropped from Tachyon,
       * and the diskSize here indicates the data size in or dropped to disk.
       * They can be both larger than 0, when a block is dropped from memory to disk.
       * Therefore, a safe way to set BlockStatus is to set its info in accurate modes. */
      if (storageLevel.useMemory) {
        _blocks.put(blockId, BlockStatus(storageLevel, memSize, 0, 0))
        _remainingMem -= memSize

        logInfo("Added %s in memory on %s (size: %s, free: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(memSize),
          Utils.bytesToString(_remainingMem)))
      }
      if (storageLevel.useDisk) {
        _blocks.put(blockId, BlockStatus(storageLevel, 0, diskSize, 0))

        logInfo("Added %s on disk on %s (size: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(diskSize)))
      }
      if (storageLevel.useOffHeap) {
        _blocks.put(blockId, BlockStatus(storageLevel, 0, 0, tachyonSize))

        logInfo("Added %s on tachyon on %s (size: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(tachyonSize)))
      }
    } else if (_blocks.containsKey(blockId)) {

      // FIXME 如果StorageLevel是非法的，而且之前保存过则BlockId，那么就将blockId从内存中删除
      // If isValid is not true, drop the block.
      val blockStatus: BlockStatus = _blocks.get(blockId)
      _blocks.remove(blockId)

      if (blockStatus.storageLevel.useMemory) {
        logInfo("Removed %s on %s in memory (size: %s, free: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(blockStatus.memSize),
          Utils.bytesToString(_remainingMem)))
      }
      if (blockStatus.storageLevel.useDisk) {
        logInfo("Removed %s on %s on disk (size: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(blockStatus.diskSize)))
      }
      if (blockStatus.storageLevel.useOffHeap) {
        logInfo("Removed %s on %s on tachyon (size: %s)".format(
          blockId, blockManagerId.hostPort, Utils.bytesToString(blockStatus.tachyonSize)))
      }
    }
  }

  def removeBlock(blockId: BlockId) {
    if (_blocks.containsKey(blockId)) {
      _remainingMem += _blocks.get(blockId).memSize
      _blocks.remove(blockId)
    }
  }

  def remainingMem: Long = _remainingMem

  def lastSeenMs: Long = _lastSeenMs

  def blocks: JHashMap[BlockId, BlockStatus] = _blocks

  override def toString: String = "BlockManagerInfo " + timeMs + " " + _remainingMem

  def clear() {
    _blocks.clear()
  }
}
