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

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.benmanes.caffeine.cache.Weigher;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCache;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCacheCaffeineImpl;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCacheNoOpImpl;
import com.google.cloud.gcs.analyticscore.common.cache.ThrowingFunction;
import com.google.cloud.gcs.analyticscore.common.cache.disk.SharedDiskCache;
import com.google.cloud.gcs.analyticscore.common.cache.disk.SharedDiskCacheOptions;
import com.google.cloud.gcs.analyticscore.common.telemetry.Telemetry;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the caching layer for GCS objects. This class is thread-safe and acts as a registry for
 * various specialized caches (e.g., Parquet footer cache).
 *
 * <p>When the disk cache is enabled, the in-memory footer and small object caches act as an L1 tier
 * over a worker-level {@link SharedDiskCache} L2 tier that is shared by every executor process on
 * the host. Only objects with a known content generation participate in the disk tier, because GCS
 * content is immutable per generation and such entries can never be stale.
 */
public class AnalyticsCacheManager {

  private static final Logger LOG = LoggerFactory.getLogger(AnalyticsCacheManager.class);

  /**
   * TTL for bucket properties cache in minutes. A 10-minute TTL is used because a bucket's
   * Hierarchical Namespace configuration is immutable unless the bucket is deleted and re-created.
   */
  private static final long BUCKET_PROPERTIES_CACHE_TTL_MINUTES = 10;

  private static final String FOOTER_DISK_ENTRY_KIND = "footer";
  private static final String SMALL_OBJECT_DISK_ENTRY_KIND = "small-object";

  private final AnalyticsCache<GcsItemId, ByteBuffer> footerCache;
  private final AnalyticsCache<GcsItemId, ByteBuffer> smallObjectCache;
  private final AnalyticsCache<String, BucketProperties> bucketPropertiesCache;
  private final Optional<SharedDiskCache> diskCache;

  /**
   * Creates a new {@link AnalyticsCacheManager} with the specified options and no telemetry
   * reporting.
   *
   * @param options The configuration options for the caching layer.
   */
  public AnalyticsCacheManager(GcsCacheOptions options) {
    this(options, new Telemetry(ImmutableList.of()));
  }

  /**
   * Creates a new {@link AnalyticsCacheManager} with the specified options.
   *
   * @param options The configuration options for the caching layer.
   * @param telemetry The telemetry used to report cache metrics.
   */
  public AnalyticsCacheManager(GcsCacheOptions options, Telemetry telemetry) {
    checkNotNull(options, "options cannot be null");
    checkNotNull(telemetry, "telemetry cannot be null");
    Weigher<GcsItemId, ByteBuffer> weigher = (key, value) -> value.remaining();
    this.footerCache =
        options.isFooterCacheEnabled()
            ? AnalyticsCacheCaffeineImpl.create(options.getFooterCacheMaxSizeBytes(), weigher)
            : AnalyticsCacheNoOpImpl.getInstance();
    this.smallObjectCache =
        options.isSmallObjectCacheEnabled()
            ? AnalyticsCacheCaffeineImpl.create(options.getSmallObjectCacheMaxSizeBytes(), weigher)
            : AnalyticsCacheNoOpImpl.getInstance();
    this.bucketPropertiesCache =
        AnalyticsCacheCaffeineImpl.createWithTtlOnly(
            BUCKET_PROPERTIES_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
    this.diskCache = createDiskCache(options, telemetry);
  }

  private static Optional<SharedDiskCache> createDiskCache(
      GcsCacheOptions options, Telemetry telemetry) {
    if (!options.isDiskCacheEnabled()) {
      return Optional.empty();
    }
    try {
      SharedDiskCacheOptions diskCacheOptions =
          SharedDiskCacheOptions.builder()
              .setCacheDirectory(options.getDiskCacheDirectory().get())
              .setMaxSizeBytes(options.getDiskCacheMaxSizeBytes())
              .setTtlMillis(options.getDiskCacheTtlMillis())
              .setEvictionHighWatermark(options.getDiskCacheEvictionHighWatermark())
              .setEvictionLowWatermark(options.getDiskCacheEvictionLowWatermark())
              .build();
      return Optional.of(SharedDiskCache.getOrCreate(diskCacheOptions, telemetry));
    } catch (IOException | RuntimeException e) {
      LOG.warn("Failed to initialize the disk cache; continuing without it", e);
      return Optional.empty();
    }
  }

  /**
   * Returns the cached footer for the given {@code itemId}, obtaining it from the {@code
   * footerLoader} if necessary. This method is atomic; the {@code footerLoader} will be applied at
   * most once per itemId during concurrent access.
   *
   * <p>If the {@code footerLoader} throws an exception, it will be propagated to the caller and the
   * result will not be cached.
   *
   * @throws IOException if the loader throws an {@link IOException}.
   */
  public ByteBuffer getFooter(GcsItemId itemId, FooterLoader footerLoader) throws IOException {
    checkNotNull(itemId, "itemId cannot be null");
    checkNotNull(footerLoader, "footerLoader cannot be null");

    return footerCache
        .get(
            itemId,
            cachedItemId ->
                loadThroughDiskCache(cachedItemId, FOOTER_DISK_ENTRY_KIND, footerLoader::load))
        .asReadOnlyBuffer();
  }

  /**
   * Returns the cached small object for the given {@code itemId}, obtaining it from the {@code
   * smallObjectLoader} if necessary. This method is atomic.
   *
   * @throws IOException if the loader throws an {@link IOException}.
   */
  public ByteBuffer getSmallObject(GcsItemId itemId, SmallObjectLoader smallObjectLoader)
      throws IOException {
    checkNotNull(itemId, "itemId cannot be null");
    checkNotNull(smallObjectLoader, "smallObjectLoader cannot be null");

    return smallObjectCache
        .get(
            itemId,
            cachedItemId ->
                loadThroughDiskCache(
                    cachedItemId, SMALL_OBJECT_DISK_ENTRY_KIND, smallObjectLoader::load))
        .asReadOnlyBuffer();
  }

  /** Invalidates the cached footer for the given {@code itemId}. */
  public void invalidateFooter(GcsItemId itemId) {
    checkNotNull(itemId, "itemId cannot be null");
    footerCache.invalidate(itemId);
    invalidateDiskEntry(itemId, FOOTER_DISK_ENTRY_KIND);
  }

  /** Invalidates the cached small object for the given {@code itemId}. */
  public void invalidateSmallObject(GcsItemId itemId) {
    checkNotNull(itemId, "itemId cannot be null");
    smallObjectCache.invalidate(itemId);
    invalidateDiskEntry(itemId, SMALL_OBJECT_DISK_ENTRY_KIND);
  }

  /**
   * Loads a value through the worker-level disk cache when it is enabled and the object's content
   * generation is known; otherwise falls straight through to the loader. Entries are keyed by
   * generation, so a cached value can never be stale.
   */
  private ByteBuffer loadThroughDiskCache(
      GcsItemId itemId,
      String entryKind,
      ThrowingFunction<GcsItemId, ByteBuffer, IOException> loader)
      throws IOException {
    if (!diskCache.isPresent() || !itemId.getContentGeneration().isPresent()) {
      return loader.apply(itemId);
    }
    return diskCache.get().getOrLoad(diskEntryKey(itemId, entryKind), () -> loader.apply(itemId));
  }

  private void invalidateDiskEntry(GcsItemId itemId, String entryKind) {
    if (diskCache.isPresent() && itemId.getContentGeneration().isPresent()) {
      diskCache.get().invalidate(diskEntryKey(itemId, entryKind));
    }
  }

  private static String diskEntryKey(GcsItemId itemId, String entryKind) {
    return itemId.getBucketName()
        + '/'
        + itemId.getObjectName().orElse("")
        + '#'
        + itemId.getContentGeneration().get()
        + '#'
        + entryKind;
  }

  /**
   * Returns the cached properties for the given {@code bucketName}, obtaining it from the {@code
   * bucketPropertiesLoader} if necessary. This method is atomic.
   *
   * @throws IOException if the loader throws an {@link IOException}.
   */
  public BucketProperties getBucketProperties(
      String bucketName, BucketPropertiesLoader bucketPropertiesLoader) throws IOException {
    checkNotNull(bucketName, "bucketName cannot be null");
    checkNotNull(bucketPropertiesLoader, "bucketPropertiesLoader cannot be null");

    return bucketPropertiesCache.get(bucketName, bucketPropertiesLoader::load);
  }

  /** Invalidates the cached properties for the given {@code bucketName}. */
  public void invalidateBucketProperties(String bucketName) {
    checkNotNull(bucketName, "bucketName cannot be null");
    bucketPropertiesCache.invalidate(bucketName);
  }

  /**
   * Invalidates all in-memory cached entries. The worker-level disk cache is left untouched: it is
   * shared with other executor processes on the host, and its generation-keyed entries cannot be
   * stale.
   */
  public void invalidateAll() {
    footerCache.invalidateAll();
    smallObjectCache.invalidateAll();
    bucketPropertiesCache.invalidateAll();
  }

  /** A loader for GCS object footers. */
  @FunctionalInterface
  public interface FooterLoader {
    /** Loads the footer for the given {@code itemId}. */
    ByteBuffer load(GcsItemId itemId) throws IOException;
  }

  /** A loader for small GCS objects. */
  @FunctionalInterface
  public interface SmallObjectLoader {
    /** Loads the small object for the given {@code itemId}. */
    ByteBuffer load(GcsItemId itemId) throws IOException;
  }

  /** A loader for GCS bucket properties. */
  @FunctionalInterface
  public interface BucketPropertiesLoader {
    /** Loads the properties for the given {@code bucketName}. */
    BucketProperties load(String bucketName) throws IOException;
  }
}
