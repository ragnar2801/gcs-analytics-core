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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Manages the caching layer for GCS objects. This class is thread-safe and acts as a registry for
 * various specialized caches (e.g., Parquet footer cache).
 *
 * <p>The footer and small-object caches can operate in two modes, selected by {@link
 * GcsCacheOptions#isSharedCacheEnabled()}:
 *
 * <ul>
 *   <li><b>Private (default):</b> each manager owns its caches, which live and die with the
 *       enclosing {@link GcsFileSystem}. This is the historical behavior.
 *   <li><b>Shared:</b> the manager delegates to process-wide caches (see {@link
 *       SharedAnalyticsCaches}) so a warm cache survives across the short-lived file systems an
 *       engine creates per task. Entries are partitioned by an authorization-boundary scope so
 *       that, even though the underlying storage is shared, a caller can only be served bytes that
 *       were read under its own scope.
 * </ul>
 *
 * <p>Sharing takes effect only when a non-empty {@link GcsCacheOptions#getCacheScope() cache scope}
 * is present. If sharing is requested without a scope, the manager falls back to private caches
 * rather than pooling entries under an empty, everyone-matches scope; this fails closed.
 */
public class AnalyticsCacheManager {

  /**
   * TTL for bucket properties cache in minutes. A 10-minute TTL is used because a bucket's
   * Hierarchical Namespace configuration is immutable unless the bucket is deleted and re-created.
   */
  private static final long BUCKET_PROPERTIES_CACHE_TTL_MINUTES = 10;

  private final AnalyticsCache<CacheKey, ByteBuffer> footerCache;
  private final AnalyticsCache<CacheKey, ByteBuffer> smallObjectCache;
  private final AnalyticsCache<String, BucketProperties> bucketPropertiesCache;

  /** The scope under which this manager reads and writes entries in the footer/small caches. */
  private final String scope;

  /**
   * Whether {@link #footerCache}/{@link #smallObjectCache} are the process-wide shared caches. When
   * {@code true}, entries survive this manager (bounded by size and TTL) and invalidation is
   * restricted to this manager's {@link #scope}; when {@code false} the caches are private and are
   * cleared wholesale when the file system closes.
   */
  private final boolean usingSharedCache;

  /**
   * Creates a new {@link AnalyticsCacheManager} with the specified options.
   *
   * @param options The configuration options for the caching layer.
   */
  public AnalyticsCacheManager(GcsCacheOptions options) {
    checkNotNull(options, "options cannot be null");

    boolean shareRequested = options.isSharedCacheEnabled();
    String requestedScope = options.getCacheScope().filter(s -> !s.isEmpty()).orElse(null);
    this.usingSharedCache = shareRequested && requestedScope != null;

    if (usingSharedCache) {
      SharedAnalyticsCaches shared = SharedAnalyticsCaches.getInstance(options);
      this.footerCache = shared.footerCache();
      this.smallObjectCache = shared.smallObjectCache();
      this.scope = requestedScope;
    } else {
      Weigher<CacheKey, ByteBuffer> weigher = (key, value) -> value.remaining();
      this.footerCache =
          options.isFooterCacheEnabled()
              ? AnalyticsCacheCaffeineImpl.create(
                  options.getFooterCacheMaxSizeBytes(), weigher, options.getFooterCacheTtlSeconds())
              : AnalyticsCacheNoOpImpl.getInstance();
      this.smallObjectCache =
          options.isSmallObjectCacheEnabled()
              ? AnalyticsCacheCaffeineImpl.create(
                  options.getSmallObjectCacheMaxSizeBytes(),
                  weigher,
                  options.getSmallObjectCacheTtlSeconds())
              : AnalyticsCacheNoOpImpl.getInstance();
      // A per-instance scope keeps composite keys well-formed. The caches are private, so this
      // value is never compared against another manager's entries.
      this.scope = "private-" + UUID.randomUUID();
    }

    this.bucketPropertiesCache =
        AnalyticsCacheCaffeineImpl.createWithTtlOnly(
            BUCKET_PROPERTIES_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
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
        .get(CacheKey.create(scope, itemId), key -> footerLoader.load(key.getItemId()))
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
        .get(CacheKey.create(scope, itemId), key -> smallObjectLoader.load(key.getItemId()))
        .asReadOnlyBuffer();
  }

  /** Invalidates the cached footer for the given {@code itemId}. */
  public void invalidateFooter(GcsItemId itemId) {
    checkNotNull(itemId, "itemId cannot be null");
    footerCache.invalidate(CacheKey.create(scope, itemId));
  }

  /** Invalidates the cached small object for the given {@code itemId}. */
  public void invalidateSmallObject(GcsItemId itemId) {
    checkNotNull(itemId, "itemId cannot be null");
    smallObjectCache.invalidate(CacheKey.create(scope, itemId));
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
   * Invalidates all cached entries visible to this manager.
   *
   * <p>For a shared cache this drops only entries belonging to this manager's scope, leaving
   * entries owned by other scopes (and therefore other credentials) untouched; for a private cache
   * it clears everything.
   */
  public void invalidateAll() {
    if (usingSharedCache) {
      footerCache.invalidateIf(key -> scope.equals(key.getScope()));
      smallObjectCache.invalidateIf(key -> scope.equals(key.getScope()));
    } else {
      footerCache.invalidateAll();
      smallObjectCache.invalidateAll();
    }
    bucketPropertiesCache.invalidateAll();
  }

  /**
   * Releases cache resources tied to the enclosing {@link GcsFileSystem} when it closes.
   *
   * <p>Private caches are cleared wholesale, matching the file system's lifetime. Shared footer and
   * small-object entries are intentionally left in place — surviving individual file systems is the
   * point of a shared cache — and remain bounded by their size limit and TTL; only the per-instance
   * bucket-properties cache is cleared.
   */
  public void onFileSystemClose() {
    if (usingSharedCache) {
      bucketPropertiesCache.invalidateAll();
    } else {
      invalidateAll();
    }
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
