/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block;

import alluxio.Configuration;
import alluxio.PropertyKey;
import alluxio.PropertyKeyFormat;
import alluxio.Sessions;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatScheduler;
import alluxio.heartbeat.HeartbeatThread;
import alluxio.heartbeat.ManuallyScheduleHeartbeat;
import alluxio.util.ThreadFactoryUtils;

import com.google.common.collect.ImmutableMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for {@link SpaceReserver}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({BlockWorker.class, BlockStoreMeta.class})
public class SpaceReserverTest {
  private ExecutorService mExecutorService;

  /** Rule to create a new temporary folder during each test. */
  @Rule
  public TemporaryFolder mTempFolder = new TemporaryFolder();

  @Rule
  public ManuallyScheduleHeartbeat mSchedule =
      new ManuallyScheduleHeartbeat(HeartbeatContext.WORKER_SPACE_RESERVER);

  @Before
  public void before() {
    mExecutorService =
        Executors.newFixedThreadPool(1, ThreadFactoryUtils.build("space-reserver-test", true));
    Configuration.defaultInit();
  }

  @After
  public void after() {
    mExecutorService.shutdownNow();
  }

  @Test
  public void reserveCorrectAmountsOfSpace() throws Exception {
    BlockWorker blockWorker = PowerMockito.mock(BlockWorker.class);
    BlockStoreMeta storeMeta = PowerMockito.mock(BlockStoreMeta.class);
    Mockito.when(blockWorker.getStoreMeta()).thenReturn(storeMeta);
    Map<String, Long> capacityBytesOnTiers = ImmutableMap.of("MEM", 400L, "HDD", 1000L);
    Mockito.when(storeMeta.getCapacityBytesOnTiers()).thenReturn(capacityBytesOnTiers);

    // Create two tiers named "MEM" and "HDD" with aliases 0 and 1.
    TieredBlockStoreTestUtils.setupConfWithMultiTier("/",
        new int[]{0, 1}, new String[] {"MEM", "HDD"},
        new String[][]{new String[]{"/a"}, new String[]{"/b"}},
        new long[][]{new long[]{0}, new long[]{0}}, "/");

    PropertyKey reserveRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_RESERVED_RATIO_FORMAT.format(0);
    Configuration.set(reserveRatioProp, "0.2");
    reserveRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_RESERVED_RATIO_FORMAT.format(1);
    Configuration.set(reserveRatioProp, "0.3");
    SpaceReserver spaceReserver = new SpaceReserver(blockWorker);

    mExecutorService.submit(
        new HeartbeatThread(HeartbeatContext.WORKER_SPACE_RESERVER, spaceReserver, 0));

    // Run the space reserver once.
    HeartbeatScheduler.execute(HeartbeatContext.WORKER_SPACE_RESERVER);

    // 400 * 0.2 = 80
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 80L, "MEM");
    // 400 * 0.2 + 1000 * 0.3 = 380
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 380L, "HDD");
  }

  @Test
  public void testLowWatermark() throws Exception {
    BlockWorker blockWorker = PowerMockito.mock(BlockWorker.class);
    BlockStoreMeta storeMeta = PowerMockito.mock(BlockStoreMeta.class);
    Mockito.when(blockWorker.getStoreMeta()).thenReturn(storeMeta);
    Map<String, Long> capacityBytesOnTiers = ImmutableMap.of("MEM", 100L, "SSD", 200L, "HDD",
        1000L);
    Map<String, Long> usedCapacityBytesOnTiers = ImmutableMap.of("MEM", 100L, "SSD", 200L, "HDD",
        1000L);
    Mockito.when(storeMeta.getCapacityBytesOnTiers()).thenReturn(capacityBytesOnTiers);
    Mockito.when(storeMeta.getUsedBytesOnTiers()).thenReturn(usedCapacityBytesOnTiers);

    // Create two tiers named "MEM", "SSD" and "HDD" with aliases 0, 1 and 2.
    TieredBlockStoreTestUtils.setupConfWithMultiTier("/",
        new int[]{0, 1, 2}, new String[] {"MEM", "SSD", "HDD"},
        new String[][]{new String[]{"/a"}, new String[]{"/b"}, new String[]{"/c"}},
        new long[][]{new long[]{0}, new long[]{0}, new long[]{0}}, "/");
    PropertyKey highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(0);
    Configuration.set(highWatermarkRatioProp, "0.9");
    PropertyKey lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(0);
    Configuration.set(lowWatermarkRatioProp, "0.8");
    highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(1);
    Configuration.set(highWatermarkRatioProp, "0.9");
    lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(1);
    Configuration.set(lowWatermarkRatioProp, "0.7");
    highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(2);
    Configuration.set(highWatermarkRatioProp, "0.8");
    lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(2);
    Configuration.set(lowWatermarkRatioProp, "0.6");
    SpaceReserver spaceReserver = new SpaceReserver(blockWorker);

    mExecutorService.submit(
        new HeartbeatThread(HeartbeatContext.WORKER_SPACE_RESERVER, spaceReserver, 0));

    // Run the space reserver once.
    HeartbeatScheduler.execute(HeartbeatContext.WORKER_SPACE_RESERVER);

    // 1000 * 0.4 + 200 * 0.3 + 100 * 0.2 = 480
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 480L, "HDD");
    // 200 * 0.3 + 100 * 0.2 = 80
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 80L, "SSD");
    // 100 * 0.2 = 20
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 20L, "MEM");
  }

  @Test
  public void testHighWatermark() throws Exception {
    BlockWorker blockWorker = PowerMockito.mock(BlockWorker.class);
    BlockStoreMeta storeMeta = PowerMockito.mock(BlockStoreMeta.class);
    Mockito.when(blockWorker.getStoreMeta()).thenReturn(storeMeta);
    Map<String, Long> capacityBytesOnTiers = ImmutableMap.of("MEM", 100L, "SSD", 200L, "HDD",
        1000L);
    Map<String, Long> usedCapacityBytesOnTiers =
        ImmutableMap.of("MEM", 100L, "SSD", 100L, "HDD", 0L);
    Mockito.when(storeMeta.getCapacityBytesOnTiers()).thenReturn(capacityBytesOnTiers);
    Mockito.when(storeMeta.getUsedBytesOnTiers()).thenReturn(usedCapacityBytesOnTiers);

    // Create two tiers named "MEM", "SSD" and "HDD" with aliases 0, 1 and 2.
    TieredBlockStoreTestUtils.setupConfWithMultiTier("/",
        new int[]{0, 1, 2}, new String[] {"MEM", "SSD", "HDD"},
        new String[][]{new String[]{"/a"}, new String[]{"/b"}, new String[]{"/c"}},
        new long[][]{new long[]{0}, new long[]{0}, new long[]{0}}, "/");
    PropertyKey highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(0);
    Configuration.set(highWatermarkRatioProp, "0.9");
    PropertyKey lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(0);
    Configuration.set(lowWatermarkRatioProp, "0.8");
    highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(1);
    Configuration.set(highWatermarkRatioProp, "0.9");
    lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(1);
    Configuration.set(lowWatermarkRatioProp, "0.7");
    highWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_HIGH_WATERMARK_RATIO_FORMAT.format(2);
    Configuration.set(highWatermarkRatioProp, "0.8");
    lowWatermarkRatioProp =
        PropertyKeyFormat.WORKER_TIERED_STORE_LEVEL_LOW_WATERMARK_RATIO_FORMAT.format(2);
    Configuration.set(lowWatermarkRatioProp, "0.6");
    SpaceReserver spaceReserver = new SpaceReserver(blockWorker);

    mExecutorService.submit(
        new HeartbeatThread(HeartbeatContext.WORKER_SPACE_RESERVER, spaceReserver, 0));

    // Run the space reserver once.
    HeartbeatScheduler.execute(HeartbeatContext.WORKER_SPACE_RESERVER);

    // 1000 * 0.4 + 200 * 0.3 + 100 * 0.2 = 480
    Mockito.verify(blockWorker, Mockito.times(0)).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID,
        480L, "HDD");
    // 200 * 0.3 + 100 * 0.2 = 80
    Mockito.verify(blockWorker, Mockito.times(0)).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID,
        80L, "SSD");
    // 100 * 0.2 = 20
    Mockito.verify(blockWorker).freeSpace(Sessions.MIGRATE_DATA_SESSION_ID, 20L, "MEM");
  }
}
