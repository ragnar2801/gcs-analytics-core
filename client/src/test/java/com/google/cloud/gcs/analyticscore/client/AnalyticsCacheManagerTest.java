/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.gcs.analyticscore.client;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsCacheManagerTest {

  private static final String BUCKET_NAME = "test-bucket";
  private static final GcsItemId ITEM_ID =
      GcsItemId.builder().setBucketName(BUCKET_NAME).setObjectName("o").build();
  private static final ByteBuffer FOOTER = ByteBuffer.wrap(new byte[] {1, 2, 3});

  private AnalyticsCacheManager manager;

  @BeforeEach
  void setUp() {
    manager =
        new AnalyticsCacheManager(GcsCacheOptions.builder().setFooterCacheEnabled(true).build());
  }

  @AfterEach
  void tearDown() {
    // The shared caches are process-wide state; reset between tests so first-wins config and
    // retained entries from one test cannot leak into the next.
    SharedAnalyticsCaches.resetForTesting();
  }

  private static GcsCacheOptions sharedOptions(String scope) {
    return GcsCacheOptions.builder()
        .setFooterCacheEnabled(true)
        .setSmallObjectCacheEnabled(true)
        .setSmallObjectCacheMaxSizeBytes(1_000)
        .setSharedCacheEnabled(true)
        .setCacheScope(scope)
        .build();
  }

  @Test
  void getFooter_notPresent_computesAndCachesValue() throws IOException {
    AtomicInteger callCount = new AtomicInteger(0);

    ByteBuffer footer =
        manager.getFooter(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return FOOTER.duplicate();
            });
    ByteBuffer secondFooter =
        manager.getFooter(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return ByteBuffer.wrap(new byte[] {4, 5, 6});
            });

    assertThat(footer).isEqualTo(FOOTER);
    assertThat(callCount.get()).isEqualTo(1);
    assertThat(secondFooter).isEqualTo(FOOTER);
    assertThat(secondFooter).isNotSameInstanceAs(FOOTER);
    assertThat(secondFooter.isReadOnly()).isTrue();
  }

  @Test
  void getFooter_loaderThrowsIOException_rethrowsIOException() {
    assertThrows(
        IOException.class,
        () ->
            manager.getFooter(
                ITEM_ID,
                itemId -> {
                  throw new IOException("test-io-exception");
                }));
  }

  @Test
  void getFooter_cacheDisabled_anyKey_callsLoaderEveryTime() throws IOException {
    manager =
        new AnalyticsCacheManager(GcsCacheOptions.builder().setFooterCacheEnabled(false).build());
    AtomicInteger callCount = new AtomicInteger(0);
    AnalyticsCacheManager.FooterLoader loader =
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        };

    manager.getFooter(ITEM_ID, loader);
    manager.getFooter(ITEM_ID, loader);

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  void invalidateFooter_cacheDisabled_anyKey_succeeds() {
    manager =
        new AnalyticsCacheManager(GcsCacheOptions.builder().setFooterCacheEnabled(false).build());

    manager.invalidateFooter(ITEM_ID);
  }

  @Test
  void invalidateAll_cacheDisabled_anyKey_succeeds() {
    manager =
        new AnalyticsCacheManager(GcsCacheOptions.builder().setFooterCacheEnabled(false).build());

    manager.invalidateAll();
  }

  @Test
  void invalidateSmallObject_present_removesEntry() throws IOException {
    GcsCacheOptions cacheOptions =
        GcsCacheOptions.builder()
            .setSmallObjectCacheEnabled(true)
            .setSmallObjectCacheMaxSizeBytes(200)
            .build();
    manager = new AnalyticsCacheManager(cacheOptions);
    manager.getSmallObject(ITEM_ID, itemId -> FOOTER.duplicate());

    manager.invalidateSmallObject(ITEM_ID);

    AtomicInteger callCount = new AtomicInteger(0);
    manager.getSmallObject(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });

    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void invalidateFooter_present_removesEntry() throws IOException {
    AtomicInteger callCount = new AtomicInteger(0);
    manager.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());

    manager.invalidateFooter(ITEM_ID);

    manager.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void invalidateAll_withEntries_clearsCache() throws IOException {
    GcsItemId itemId2 = GcsItemId.builder().setBucketName(BUCKET_NAME).setObjectName("o2").build();
    manager.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());
    manager.getFooter(itemId2, itemId -> ByteBuffer.wrap(new byte[] {2}));
    manager.getBucketProperties(BUCKET_NAME, bucketName -> BucketProperties.create(true));
    AtomicInteger callCount = new AtomicInteger(0);

    manager.invalidateAll();

    manager.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    manager.getFooter(
        itemId2,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    manager.getBucketProperties(
        BUCKET_NAME,
        bucketName -> {
          callCount.incrementAndGet();
          return BucketProperties.create(true);
        });
    assertThat(callCount.get()).isEqualTo(3);
  }

  @Test
  void getBucketProperties_notPresent_computesAndCachesValue() throws IOException {
    AtomicInteger callCount = new AtomicInteger(0);
    BucketProperties bucketProperties = BucketProperties.create(true);

    BucketProperties result1 =
        manager.getBucketProperties(
            BUCKET_NAME,
            bucketName -> {
              callCount.incrementAndGet();
              return bucketProperties;
            });
    BucketProperties result2 =
        manager.getBucketProperties(
            BUCKET_NAME,
            bucketName -> {
              callCount.incrementAndGet();
              return BucketProperties.create(false);
            });

    assertThat(result1).isEqualTo(bucketProperties);
    assertThat(result2).isEqualTo(bucketProperties);
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void getBucketProperties_loaderThrowsIOException_rethrowsIOException() {
    assertThrows(
        IOException.class,
        () ->
            manager.getBucketProperties(
                BUCKET_NAME,
                bucketName -> {
                  throw new IOException("test-io-exception");
                }));
  }

  @Test
  void invalidateBucketProperties_present_removesEntry() throws IOException {
    AtomicInteger callCount = new AtomicInteger(0);
    manager.getBucketProperties(BUCKET_NAME, bucketName -> BucketProperties.create(true));

    manager.invalidateBucketProperties(BUCKET_NAME);
    manager.getBucketProperties(
        BUCKET_NAME,
        bucketName -> {
          callCount.incrementAndGet();
          return BucketProperties.create(true);
        });

    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void getFooter_sharedCacheSameScope_sharesEntriesAcrossManagers() throws IOException {
    AnalyticsCacheManager first = new AnalyticsCacheManager(sharedOptions("gs://bucket/table/"));
    AnalyticsCacheManager second = new AnalyticsCacheManager(sharedOptions("gs://bucket/table/"));
    AtomicInteger callCount = new AtomicInteger(0);

    first.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    ByteBuffer fromSecond =
        second.getFooter(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return ByteBuffer.wrap(new byte[] {9});
            });

    // The second, independent manager is served the entry the first one loaded.
    assertThat(callCount.get()).isEqualTo(1);
    assertThat(fromSecond).isEqualTo(FOOTER);
  }

  @Test
  void getFooter_sharedCacheDifferentScope_doesNotShareEntries() throws IOException {
    AnalyticsCacheManager tableA = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableA/"));
    AnalyticsCacheManager tableB = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableB/"));
    ByteBuffer footerA = ByteBuffer.wrap(new byte[] {1});
    ByteBuffer footerB = ByteBuffer.wrap(new byte[] {2});
    AtomicInteger callCount = new AtomicInteger(0);

    tableA.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return footerA.duplicate();
        });
    ByteBuffer fromB =
        tableB.getFooter(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return footerB.duplicate();
            });

    // Same object, different authorization scopes: B must load its own bytes, never be served A's.
    assertThat(callCount.get()).isEqualTo(2);
    assertThat(fromB).isEqualTo(footerB);
  }

  @Test
  void getSmallObject_sharedCacheDifferentScope_doesNotShareEntries() throws IOException {
    AnalyticsCacheManager tableA = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableA/"));
    AnalyticsCacheManager tableB = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableB/"));
    ByteBuffer objectB = ByteBuffer.wrap(new byte[] {2});
    AtomicInteger callCount = new AtomicInteger(0);

    tableA.getSmallObject(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return ByteBuffer.wrap(new byte[] {1});
        });
    ByteBuffer fromB =
        tableB.getSmallObject(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return objectB.duplicate();
            });

    assertThat(callCount.get()).isEqualTo(2);
    assertThat(fromB).isEqualTo(objectB);
  }

  @Test
  void getFooter_sharedRequestedWithoutScope_fallsBackToPrivateCaches() throws IOException {
    GcsCacheOptions noScope =
        GcsCacheOptions.builder().setFooterCacheEnabled(true).setSharedCacheEnabled(true).build();
    AnalyticsCacheManager first = new AnalyticsCacheManager(noScope);
    AnalyticsCacheManager second = new AnalyticsCacheManager(noScope);
    AtomicInteger callCount = new AtomicInteger(0);

    first.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    second.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });

    // No scope means no sharing: each manager keeps a private cache, so both loaders run.
    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  void onFileSystemClose_privateCache_clearsEntries() throws IOException {
    manager.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());
    AtomicInteger callCount = new AtomicInteger(0);

    manager.onFileSystemClose();

    manager.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  void onFileSystemClose_sharedCache_retainsEntriesForLaterManagers() throws IOException {
    AnalyticsCacheManager first = new AnalyticsCacheManager(sharedOptions("gs://bucket/table/"));
    first.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());

    first.onFileSystemClose();

    AnalyticsCacheManager second = new AnalyticsCacheManager(sharedOptions("gs://bucket/table/"));
    AtomicInteger callCount = new AtomicInteger(0);
    ByteBuffer fromSecond =
        second.getFooter(
            ITEM_ID,
            itemId -> {
              callCount.incrementAndGet();
              return ByteBuffer.wrap(new byte[] {9});
            });

    // Surviving individual file-system instances is the point of a shared cache.
    assertThat(callCount.get()).isEqualTo(0);
    assertThat(fromSecond).isEqualTo(FOOTER);
  }

  @Test
  void invalidateAll_sharedCache_clearsOnlyOwnScope() throws IOException {
    AnalyticsCacheManager tableA = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableA/"));
    AnalyticsCacheManager tableB = new AnalyticsCacheManager(sharedOptions("gs://bucket/tableB/"));
    tableA.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());
    tableB.getFooter(ITEM_ID, itemId -> FOOTER.duplicate());

    tableA.invalidateAll();

    AtomicInteger callCount = new AtomicInteger(0);
    tableA.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });
    tableB.getFooter(
        ITEM_ID,
        itemId -> {
          callCount.incrementAndGet();
          return FOOTER.duplicate();
        });

    // Only scope A was invalidated: A reloads (loader runs), B is still a hit (loader does not).
    assertThat(callCount.get()).isEqualTo(1);
  }
}
