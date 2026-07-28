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

import com.github.benmanes.caffeine.cache.Weigher;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCache;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCacheCaffeineImpl;
import com.google.cloud.gcs.analyticscore.common.cache.AnalyticsCacheNoOpImpl;
import com.google.common.annotations.VisibleForTesting;
import java.nio.ByteBuffer;

/**
 * JVM-wide footer and small-object caches, shared across {@link AnalyticsCacheManager} instances.
 *
 * <p>The footer cache is bound to the lifecycle of a {@link GcsFileSystem} instance, which makes it
 * ineffective for engines (such as Apache Iceberg) that create a fresh, short-lived file system per
 * task and never reuse it: each instance starts with a cold cache. Holding the caches statically
 * lets those instances share a warm cache for the lifetime of the JVM (typically an executor).
 *
 * <p>Because the caches are shared, entries are keyed by {@link CacheKey}, which pairs the {@link
 * GcsItemId} with an authorization-boundary scope. An entry is only ever returned to a caller that
 * presents the same scope the entry was loaded under, so sharing a static cache does not let bytes
 * read under one credential be served to a caller holding a different one. See {@link CacheKey}.
 *
 * <p>The caches are initialized once, lazily, from the first {@link GcsCacheOptions} that enables
 * shared caching; later differing options do not rebuild them. This first-wins behavior is
 * acceptable because cache sizing and TTL are process-level concerns and are expected to be uniform
 * within a JVM.
 */
final class SharedAnalyticsCaches {

  private static volatile SharedAnalyticsCaches instance;

  private final AnalyticsCache<CacheKey, ByteBuffer> footerCache;
  private final AnalyticsCache<CacheKey, ByteBuffer> smallObjectCache;

  private SharedAnalyticsCaches(GcsCacheOptions options) {
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
  }

  /** Returns the process-wide shared caches, initializing them from {@code options} if needed. */
  static SharedAnalyticsCaches getInstance(GcsCacheOptions options) {
    SharedAnalyticsCaches local = instance;
    if (local == null) {
      synchronized (SharedAnalyticsCaches.class) {
        local = instance;
        if (local == null) {
          local = new SharedAnalyticsCaches(options);
          instance = local;
        }
      }
    }
    return local;
  }

  AnalyticsCache<CacheKey, ByteBuffer> footerCache() {
    return footerCache;
  }

  AnalyticsCache<CacheKey, ByteBuffer> smallObjectCache() {
    return smallObjectCache;
  }

  /** Drops the process-wide caches so the next {@link #getInstance} rebuilds them. For tests. */
  @VisibleForTesting
  static void resetForTesting() {
    synchronized (SharedAnalyticsCaches.class) {
      instance = null;
    }
  }
}
