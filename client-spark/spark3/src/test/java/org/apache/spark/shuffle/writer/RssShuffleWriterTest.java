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

package org.apache.spark.shuffle.writer;


import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.spark.Partitioner;
import org.apache.spark.ShuffleDependency;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.executor.ShuffleWriteMetrics;
import org.apache.spark.executor.TaskMetrics;
import org.apache.spark.memory.TaskMemoryManager;
import org.apache.spark.serializer.KryoSerializer;
import org.apache.spark.serializer.Serializer;
import org.apache.spark.shuffle.RssShuffleHandle;
import org.apache.spark.shuffle.RssShuffleManager;
import org.apache.spark.shuffle.RssSparkConfig;
import org.apache.spark.shuffle.TestUtils;
import org.apache.spark.util.EventLoop;
import org.junit.jupiter.api.Test;
import scala.Product2;
import scala.Tuple2;
import scala.collection.mutable.MutableList;

import org.apache.uniffle.client.api.ShuffleWriteClient;
import org.apache.uniffle.common.ShuffleBlockInfo;
import org.apache.uniffle.common.ShuffleServerInfo;
import org.apache.uniffle.common.config.RssConf;
import org.apache.uniffle.storage.util.StorageType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public class RssShuffleWriterTest {

  @Test
  public void checkBlockSendResultTest() {
    SparkConf conf = new SparkConf();
    conf.setAppName("testApp")
        .setMaster("local[2]")
        .set(RssSparkConfig.RSS_TEST_FLAG.key(), "true")
        .set(RssSparkConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS.key(), "10000")
        .set(RssSparkConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS.key(), "1000")
        .set(RssSparkConfig.RSS_STORAGE_TYPE.key(), StorageType.LOCALFILE.name())
        .set(RssSparkConfig.RSS_COORDINATOR_QUORUM.key(), "127.0.0.1:12345,127.0.0.1:12346");
    // init SparkContext
    final SparkContext sc = SparkContext.getOrCreate(conf);
    Map<String, Set<Long>> failBlocks = Maps.newConcurrentMap();
    Map<String, Set<Long>> successBlocks = Maps.newConcurrentMap();
    Serializer kryoSerializer = new KryoSerializer(conf);
    RssShuffleManager manager = TestUtils.createShuffleManager(
        conf,
        false,
        null,
        successBlocks,
        failBlocks);

    ShuffleWriteClient mockShuffleWriteClient = mock(ShuffleWriteClient.class);
    Partitioner mockPartitioner = mock(Partitioner.class);
    RssShuffleHandle mockHandle = mock(RssShuffleHandle.class);
    ShuffleDependency mockDependency = mock(ShuffleDependency.class);
    when(mockHandle.getDependency()).thenReturn(mockDependency);
    when(mockPartitioner.numPartitions()).thenReturn(2);
    TaskMemoryManager mockTaskMemoryManager = mock(TaskMemoryManager.class);
    when(mockHandle.getPartitionToServers()).thenReturn(Maps.newHashMap());
    when(mockDependency.partitioner()).thenReturn(mockPartitioner);

    BufferManagerOptions bufferOptions = new BufferManagerOptions(conf);
    WriteBufferManager bufferManager = new WriteBufferManager(
        0, 0, bufferOptions, kryoSerializer,
        Maps.newHashMap(), mockTaskMemoryManager, new ShuffleWriteMetrics(), new RssConf());
    WriteBufferManager bufferManagerSpy = spy(bufferManager);

    RssShuffleWriter rssShuffleWriter = new RssShuffleWriter("appId", 0, "taskId", 1L,
        bufferManagerSpy, (new TaskMetrics()).shuffleWriteMetrics(),
        manager, conf, mockShuffleWriteClient, mockHandle);
    doReturn(1000000L).when(bufferManagerSpy).acquireMemory(anyLong());

    // case 1: all blocks are sent successfully
    successBlocks.put("taskId", Sets.newHashSet(1L, 2L, 3L));
    rssShuffleWriter.checkBlockSendResult(Sets.newHashSet(1L, 2L, 3L));
    successBlocks.clear();

    // case 2: partial blocks aren't sent before spark.rss.writer.send.check.timeout,
    // Runtime exception will be thrown
    successBlocks.put("taskId", Sets.newHashSet(1L, 2L));
    Throwable e2 = assertThrows(RuntimeException.class, () ->
        rssShuffleWriter.checkBlockSendResult(Sets.newHashSet(1L, 2L, 3L)));
    assertTrue(e2.getMessage().startsWith("Timeout:"));
    successBlocks.clear();

    // case 3: partial blocks are sent failed, Runtime exception will be thrown
    successBlocks.put("taskId", Sets.newHashSet(1L, 2L));
    failBlocks.put("taskId", Sets.newHashSet(3L));
    Throwable e3 = assertThrows(RuntimeException.class, () ->
        rssShuffleWriter.checkBlockSendResult(Sets.newHashSet(1L, 2L, 3L)));
    assertTrue(e3.getMessage().startsWith("Send failed:"));
    successBlocks.clear();
    failBlocks.clear();

    sc.stop();
  }

  @Test
  public void writeTest() throws Exception {
    SparkConf conf = new SparkConf();
    conf.setAppName("testApp").setMaster("local[2]")
        .set(RssSparkConfig.RSS_WRITER_SERIALIZER_BUFFER_SIZE.key(), "32")
        .set(RssSparkConfig.RSS_WRITER_BUFFER_SIZE.key(), "32")
        .set(RssSparkConfig.RSS_TEST_FLAG.key(), "true")
        .set(RssSparkConfig.RSS_WRITER_BUFFER_SEGMENT_SIZE.key(), "64")
        .set(RssSparkConfig.RSS_CLIENT_SEND_CHECK_TIMEOUT_MS.key(), "10000")
        .set(RssSparkConfig.RSS_CLIENT_SEND_CHECK_INTERVAL_MS.key(), "1000")
        .set(RssSparkConfig.RSS_WRITER_BUFFER_SPILL_SIZE.key(), "128")
        .set(RssSparkConfig.RSS_STORAGE_TYPE.key(), StorageType.LOCALFILE.name())
        .set(RssSparkConfig.RSS_COORDINATOR_QUORUM.key(), "127.0.0.1:12345,127.0.0.1:12346");
    // init SparkContext
    List<ShuffleBlockInfo> shuffleBlockInfos = Lists.newArrayList();
    final SparkContext sc = SparkContext.getOrCreate(conf);
    Map<String, Set<Long>> successBlockIds = Maps.newConcurrentMap();
    EventLoop<AddBlockEvent> testLoop = new EventLoop<AddBlockEvent>("test") {
      @Override
      public void onReceive(AddBlockEvent event) {
        assertEquals("taskId", event.getTaskId());
        shuffleBlockInfos.addAll(event.getShuffleDataInfoList());
        Set<Long> blockIds = event.getShuffleDataInfoList().parallelStream()
            .map(sdi -> sdi.getBlockId()).collect(Collectors.toSet());
        successBlockIds.putIfAbsent(event.getTaskId(), Sets.newConcurrentHashSet());
        successBlockIds.get(event.getTaskId()).addAll(blockIds);
      }

      @Override
      public void onError(Throwable e) {
      }
    };

    final RssShuffleManager manager = TestUtils.createShuffleManager(
        conf,
        false,
        testLoop,
        successBlockIds,
        Maps.newConcurrentMap());
    Serializer kryoSerializer = new KryoSerializer(conf);
    Partitioner mockPartitioner = mock(Partitioner.class);
    final ShuffleWriteClient mockShuffleWriteClient = mock(ShuffleWriteClient.class);
    ShuffleDependency mockDependency = mock(ShuffleDependency.class);
    RssShuffleHandle mockHandle = mock(RssShuffleHandle.class);
    when(mockHandle.getDependency()).thenReturn(mockDependency);
    when(mockDependency.serializer()).thenReturn(kryoSerializer);
    when(mockDependency.partitioner()).thenReturn(mockPartitioner);
    when(mockPartitioner.numPartitions()).thenReturn(3);

    Map<Integer, List<ShuffleServerInfo>> partitionToServers = Maps.newHashMap();
    List<ShuffleServerInfo> ssi34 = Arrays.asList(new ShuffleServerInfo("id3", "0.0.0.3", 100),
        new ShuffleServerInfo("id4", "0.0.0.4", 100));
    partitionToServers.put(1, ssi34);
    List<ShuffleServerInfo> ssi56 = Arrays.asList(new ShuffleServerInfo("id5", "0.0.0.5", 100),
        new ShuffleServerInfo("id6", "0.0.0.6", 100));
    partitionToServers.put(2, ssi56);
    List<ShuffleServerInfo> ssi12 = Arrays.asList(new ShuffleServerInfo("id1", "0.0.0.1", 100),
        new ShuffleServerInfo("id2", "0.0.0.2", 100));
    partitionToServers.put(0, ssi12);
    when(mockPartitioner.getPartition("testKey1")).thenReturn(0);
    when(mockPartitioner.getPartition("testKey2")).thenReturn(1);
    when(mockPartitioner.getPartition("testKey4")).thenReturn(0);
    when(mockPartitioner.getPartition("testKey5")).thenReturn(1);
    when(mockPartitioner.getPartition("testKey3")).thenReturn(2);
    when(mockPartitioner.getPartition("testKey7")).thenReturn(0);
    when(mockPartitioner.getPartition("testKey8")).thenReturn(1);
    when(mockPartitioner.getPartition("testKey9")).thenReturn(2);
    when(mockPartitioner.getPartition("testKey6")).thenReturn(2);

    TaskMemoryManager mockTaskMemoryManager = mock(TaskMemoryManager.class);

    BufferManagerOptions bufferOptions = new BufferManagerOptions(conf);
    ShuffleWriteMetrics shuffleWriteMetrics = new ShuffleWriteMetrics();
    WriteBufferManager bufferManager = new WriteBufferManager(
        0, 0, bufferOptions, kryoSerializer,
        partitionToServers, mockTaskMemoryManager, shuffleWriteMetrics, new RssConf());
    WriteBufferManager bufferManagerSpy = spy(bufferManager);
    RssShuffleWriter rssShuffleWriter = new RssShuffleWriter("appId", 0, "taskId", 1L,
        bufferManagerSpy, shuffleWriteMetrics, manager, conf, mockShuffleWriteClient, mockHandle);
    doReturn(1000000L).when(bufferManagerSpy).acquireMemory(anyLong());


    RssShuffleWriter<String, String, String> rssShuffleWriterSpy = spy(rssShuffleWriter);
    doNothing().when(rssShuffleWriterSpy).sendCommit();

    // case 1
    MutableList<Product2<String, String>> data = new MutableList();
    data.appendElem(new Tuple2("testKey2", "testValue2"));
    data.appendElem(new Tuple2("testKey3", "testValue3"));
    data.appendElem(new Tuple2("testKey4", "testValue4"));
    data.appendElem(new Tuple2("testKey6", "testValue6"));
    data.appendElem(new Tuple2("testKey1", "testValue1"));
    data.appendElem(new Tuple2("testKey5", "testValue5"));
    rssShuffleWriterSpy.write(data.iterator());

    assertTrue(shuffleWriteMetrics.writeTime() > 0);
    assertEquals(6, shuffleWriteMetrics.recordsWritten());

    assertEquals(
        shuffleBlockInfos.stream().mapToInt(ShuffleBlockInfo::getLength).sum(),
        shuffleWriteMetrics.bytesWritten()
    );

    assertEquals(6, shuffleBlockInfos.size());
    for (ShuffleBlockInfo shuffleBlockInfo : shuffleBlockInfos) {
      assertEquals(22, shuffleBlockInfo.getUncompressLength());
      assertEquals(0, shuffleBlockInfo.getShuffleId());
      if (shuffleBlockInfo.getPartitionId() == 0) {
        assertEquals(shuffleBlockInfo.getShuffleServerInfos(), ssi12);
      }
      if (shuffleBlockInfo.getPartitionId() == 1) {
        assertEquals(shuffleBlockInfo.getShuffleServerInfos(), ssi34);
      }
      if (shuffleBlockInfo.getPartitionId() == 2) {
        assertEquals(shuffleBlockInfo.getShuffleServerInfos(), ssi56);
      }
      if (shuffleBlockInfo.getPartitionId() < 0 || shuffleBlockInfo.getPartitionId() > 2) {
        throw new Exception("Shouldn't be here");
      }
    }
    Map<Integer, Set<Long>> partitionToBlockIds = rssShuffleWriterSpy.getPartitionToBlockIds();
    assertEquals(2, partitionToBlockIds.get(1).size());
    assertEquals(2, partitionToBlockIds.get(0).size());
    assertEquals(2, partitionToBlockIds.get(2).size());
    partitionToBlockIds.clear();
    sc.stop();
  }

  @Test
  public void postBlockEventTest() throws Exception {
    WriteBufferManager mockBufferManager = mock(WriteBufferManager.class);
    ShuffleDependency mockDependency = mock(ShuffleDependency.class);
    ShuffleWriteMetrics mockMetrics = mock(ShuffleWriteMetrics.class);
    Partitioner mockPartitioner = mock(Partitioner.class);
    when(mockDependency.partitioner()).thenReturn(mockPartitioner);
    SparkConf sparkConf = new SparkConf();
    when(mockPartitioner.numPartitions()).thenReturn(2);
    List<AddBlockEvent> events = Lists.newArrayList();

    EventLoop<AddBlockEvent> eventLoop = new EventLoop<AddBlockEvent>("test") {
      @Override
      public void onReceive(AddBlockEvent event) {
        events.add(event);
      }

      @Override
      public void onError(Throwable e) {
      }
    };
    RssShuffleManager mockShuffleManager = spy(TestUtils.createShuffleManager(
        sparkConf,
        false,
        eventLoop,
        Maps.newConcurrentMap(),
        Maps.newConcurrentMap()));

    RssShuffleHandle mockHandle = mock(RssShuffleHandle.class);
    when(mockHandle.getDependency()).thenReturn(mockDependency);
    ShuffleWriteClient mockWriteClient = mock(ShuffleWriteClient.class);
    SparkConf conf = new SparkConf();
    conf.set(RssSparkConfig.RSS_CLIENT_SEND_SIZE_LIMIT.key(), "64")
        .set(RssSparkConfig.RSS_STORAGE_TYPE.key(), StorageType.MEMORY_LOCALFILE.name());
    List<ShuffleBlockInfo> shuffleBlockInfoList = createShuffleBlockList(1, 31);
    RssShuffleWriter writer = new RssShuffleWriter("appId", 0, "taskId", 1L,
        mockBufferManager, mockMetrics, mockShuffleManager, conf, mockWriteClient, mockHandle);
    writer.postBlockEvent(shuffleBlockInfoList);
    Thread.sleep(500);
    assertEquals(1, events.size());
    assertEquals(1, events.get(0).getShuffleDataInfoList().size());
    events.clear();

    testSingleEvent(events, writer, 2, 15);

    testSingleEvent(events, writer, 1, 33);

    testSingleEvent(events, writer, 2, 16);

    testSingleEvent(events, writer, 2, 15);

    testSingleEvent(events, writer, 2, 17);

    testSingleEvent(events, writer, 2, 32);

    testTwoEvents(events, writer, 2, 33, 1, 1);

    testTwoEvents(events, writer, 3, 17, 2, 1);
  }

  private void testTwoEvents(
      List<AddBlockEvent> events,
      RssShuffleWriter writer,
      int blockNum,
      int blockLength,
      int firstEventSize,
      int secondEventSize) throws InterruptedException {
    List<ShuffleBlockInfo> shuffleBlockInfoList;
    shuffleBlockInfoList = createShuffleBlockList(blockNum, blockLength);
    writer.postBlockEvent(shuffleBlockInfoList);
    Thread.sleep(500);
    assertEquals(2, events.size());
    assertEquals(firstEventSize, events.get(0).getShuffleDataInfoList().size());
    assertEquals(secondEventSize, events.get(1).getShuffleDataInfoList().size());
    events.clear();
  }

  private void testSingleEvent(
      List<AddBlockEvent> events,
      RssShuffleWriter writer,
      int blockNum,
      int blockLength) throws InterruptedException {
    List<ShuffleBlockInfo> shuffleBlockInfoList;
    shuffleBlockInfoList = createShuffleBlockList(blockNum, blockLength);
    writer.postBlockEvent(shuffleBlockInfoList);
    Thread.sleep(500);
    assertEquals(1, events.size());
    assertEquals(blockNum, events.get(0).getShuffleDataInfoList().size());
    events.clear();
  }

  private List<ShuffleBlockInfo> createShuffleBlockList(int blockNum, int blockLength) {
    List<ShuffleServerInfo> shuffleServerInfoList =
        Lists.newArrayList(new ShuffleServerInfo("id", "host", 0));
    List<ShuffleBlockInfo> shuffleBlockInfoList = Lists.newArrayList();
    for (int i = 0; i < blockNum; i++) {
      shuffleBlockInfoList.add(new ShuffleBlockInfo(
          0, 0, 10, blockLength, 10, new byte[]{1},
          shuffleServerInfoList, blockLength, 10, 0));
    }
    return shuffleBlockInfoList;
  }
}
