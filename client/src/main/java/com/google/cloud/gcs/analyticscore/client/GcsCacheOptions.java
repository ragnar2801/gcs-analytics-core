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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import java.util.Map;
import java.util.Optional;

/** Configuration options for the GCS caching layer. */
@AutoValue
public abstract class GcsCacheOptions {

  static final String FOOTER_CACHE_ENABLED_KEY = "analytics-core.footer.cache.enabled";
  static final String FOOTER_CACHE_MAX_SIZE_BYTES_KEY =
      "analytics-core.footer.cache.max-size-bytes";
  static final String FOOTER_CACHE_TTL_SECONDS_KEY = "analytics-core.footer.cache.ttl-seconds";
  static final String SMALL_FILE_CACHE_ENABLED_KEY = "analytics-core.small-file.cache.enabled";
  static final String SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY =
      "analytics-core.small-file.cache.max-size-bytes";
  static final String SMALL_FILE_CACHE_TTL_SECONDS_KEY =
      "analytics-core.small-file.cache.ttl-seconds";
  static final String SHARED_CACHE_ENABLED_KEY = "analytics-core.cache.shared.enabled";

  private static final long KB = 1024L;
  private static final long MB = 1024L * KB;

  private static final boolean DEFAULT_FOOTER_CACHE_ENABLED = false;
  private static final long DEFAULT_FOOTER_CACHE_MAX_SIZE_BYTES = 100 * MB;
  private static final boolean DEFAULT_SMALL_OBJECT_CACHE_ENABLED = false;
  private static final long DEFAULT_SMALL_OBJECT_CACHE_MAX_SIZE_BYTES = 200 * MB;
  private static final long DEFAULT_CACHE_TTL_SECONDS = 0;
  private static final boolean DEFAULT_SHARED_CACHE_ENABLED = false;

  /** Returns whether the Parquet footer cache is enabled. */
  public abstract boolean isFooterCacheEnabled();

  /** Returns the maximum capacity (in bytes) to hold in the Parquet footer cache. */
  public abstract long getFooterCacheMaxSizeBytes();

  /**
   * Returns the time-to-live (in seconds) for footer cache entries, or {@code 0} to disable
   * time-based expiry. See {@link #getSmallObjectCacheTtlSeconds()} for why this matters.
   */
  public abstract long getFooterCacheTtlSeconds();

  /** Returns the maximum capacity (in bytes) to hold in the small object cache. */
  /** Returns whether the small object cache is enabled. */
  public abstract boolean isSmallObjectCacheEnabled();

  /** Returns the maximum capacity (in bytes) to hold in the small object cache. */
  public abstract long getSmallObjectCacheMaxSizeBytes();

  /**
   * Returns the time-to-live (in seconds) for small-object cache entries, or {@code 0} to disable
   * time-based expiry.
   *
   * <p>Cached bytes are served without contacting GCS, so no credential is checked on a cache hit.
   * A TTL bounds how long a cached object can be served after the read that loaded it, which is
   * what keeps data from remaining accessible after the credential that authorized it has expired
   * or been revoked. When caching data read under short-lived vended credentials, set this at or
   * below the credential lifetime.
   */
  public abstract long getSmallObjectCacheTtlSeconds();

  /**
   * Returns whether the footer and small-object caches are shared across {@link
   * AnalyticsCacheManager} instances in the JVM (for example, reused across the short-lived file
   * systems an engine creates per task). When {@code false} each manager keeps private caches,
   * which is the historical behavior. Sharing only takes effect when a {@link #getCacheScope()
   * cache scope} is also present; without a scope, caches stay private so that bytes read under one
   * credential can never be served to another.
   */
  public abstract boolean isSharedCacheEnabled();

  /**
   * Returns the authorization-boundary token used to partition a shared cache, if any.
   *
   * <p>This is deliberately not read from the options map: it is a security boundary, and a
   * caller-supplied property could otherwise be set to another principal's scope to obtain their
   * cached bytes. It must be supplied programmatically by the integration layer from an authority's
   * grant (for example, the prefix a credential was vended for), never inferred from observed
   * reads.
   */
  public abstract Optional<String> getCacheScope();

  /**
   * Returns a builder for {@link GcsCacheOptions} with the same property values as this instance.
   */
  public abstract Builder toBuilder();

  /** Returns a new builder for {@link GcsCacheOptions} with default values. */
  public static Builder builder() {
    return new AutoValue_GcsCacheOptions.Builder()
        .setFooterCacheEnabled(DEFAULT_FOOTER_CACHE_ENABLED)
        .setFooterCacheMaxSizeBytes(DEFAULT_FOOTER_CACHE_MAX_SIZE_BYTES)
        .setFooterCacheTtlSeconds(DEFAULT_CACHE_TTL_SECONDS)
        .setSmallObjectCacheEnabled(DEFAULT_SMALL_OBJECT_CACHE_ENABLED)
        .setSmallObjectCacheMaxSizeBytes(DEFAULT_SMALL_OBJECT_CACHE_MAX_SIZE_BYTES)
        .setSmallObjectCacheTtlSeconds(DEFAULT_CACHE_TTL_SECONDS)
        .setSharedCacheEnabled(DEFAULT_SHARED_CACHE_ENABLED);
  }

  /** Creates a {@link GcsCacheOptions} instance from a map of configuration options. */
  public static GcsCacheOptions createFromOptions(
      Map<String, String> analyticsCoreOptions, String prefix) {
    GcsCacheOptions.Builder optionsBuilder = builder();
    if (analyticsCoreOptions.containsKey(prefix + FOOTER_CACHE_ENABLED_KEY)) {
      optionsBuilder.setFooterCacheEnabled(
          Boolean.parseBoolean(analyticsCoreOptions.get(prefix + FOOTER_CACHE_ENABLED_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + FOOTER_CACHE_MAX_SIZE_BYTES_KEY)) {
      optionsBuilder.setFooterCacheMaxSizeBytes(
          Long.parseLong(analyticsCoreOptions.get(prefix + FOOTER_CACHE_MAX_SIZE_BYTES_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + FOOTER_CACHE_TTL_SECONDS_KEY)) {
      optionsBuilder.setFooterCacheTtlSeconds(
          Long.parseLong(analyticsCoreOptions.get(prefix + FOOTER_CACHE_TTL_SECONDS_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + SMALL_FILE_CACHE_ENABLED_KEY)) {
      optionsBuilder.setSmallObjectCacheEnabled(
          Boolean.parseBoolean(analyticsCoreOptions.get(prefix + SMALL_FILE_CACHE_ENABLED_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY)) {
      optionsBuilder.setSmallObjectCacheMaxSizeBytes(
          Long.parseLong(analyticsCoreOptions.get(prefix + SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + SMALL_FILE_CACHE_TTL_SECONDS_KEY)) {
      optionsBuilder.setSmallObjectCacheTtlSeconds(
          Long.parseLong(analyticsCoreOptions.get(prefix + SMALL_FILE_CACHE_TTL_SECONDS_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + SHARED_CACHE_ENABLED_KEY)) {
      optionsBuilder.setSharedCacheEnabled(
          Boolean.parseBoolean(analyticsCoreOptions.get(prefix + SHARED_CACHE_ENABLED_KEY)));
    }
    // NOTE: cacheScope is intentionally not read from the options map; it is a security boundary
    // and must be supplied programmatically. See getCacheScope().

    return optionsBuilder.build();
  }

  /** Builder for {@link GcsCacheOptions}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /** Sets whether the Parquet footer cache is enabled. */
    public abstract Builder setFooterCacheEnabled(boolean footerCacheEnabled);

    /** Sets the maximum capacity (in bytes) to hold in the Parquet footer cache. */
    public abstract Builder setFooterCacheMaxSizeBytes(long footerCacheMaxSizeBytes);

    /** Sets the time-to-live (in seconds) for footer cache entries ({@code 0} disables it). */
    public abstract Builder setFooterCacheTtlSeconds(long footerCacheTtlSeconds);

    /** Sets the maximum capacity (in bytes) to hold in the small object cache. */
    /** Sets whether the small object cache is enabled. */
    public abstract Builder setSmallObjectCacheEnabled(boolean smallObjectCacheEnabled);

    /** Sets the maximum capacity (in bytes) to hold in the small object cache. */
    public abstract Builder setSmallObjectCacheMaxSizeBytes(long smallObjectCacheMaxSizeBytes);

    /**
     * Sets the time-to-live (in seconds) for small-object cache entries ({@code 0} disables it).
     */
    public abstract Builder setSmallObjectCacheTtlSeconds(long smallObjectCacheTtlSeconds);

    /** Sets whether the footer and small-object caches are shared across managers in the JVM. */
    public abstract Builder setSharedCacheEnabled(boolean sharedCacheEnabled);

    /**
     * Sets the authorization-boundary token that partitions a shared cache. Supply this only from
     * an authority's grant; see {@link GcsCacheOptions#getCacheScope()}.
     */
    public abstract Builder setCacheScope(String cacheScope);

    abstract GcsCacheOptions autoBuild();

    /**
     * Builds the {@link GcsCacheOptions} instance.
     *
     * @throws IllegalArgumentException if {@code footerCacheMaxSizeBytes} is non-positive when
     *     {@code footerCacheEnabled} is {@code true}.
     */
    public GcsCacheOptions build() {
      GcsCacheOptions options = autoBuild();
      if (options.isFooterCacheEnabled()) {
        checkArgument(
            options.getFooterCacheMaxSizeBytes() > 0,
            "footerCacheMaxSizeBytes must be positive when footerCacheEnabled is true");
      }
      if (options.isSmallObjectCacheEnabled()) {
        checkArgument(
            options.getSmallObjectCacheMaxSizeBytes() > 0,
            "smallObjectCacheMaxSizeBytes must be positive when smallObjectCacheEnabled is true");
      }
      checkArgument(
          options.getFooterCacheTtlSeconds() >= 0, "footerCacheTtlSeconds cannot be negative");
      checkArgument(
          options.getSmallObjectCacheTtlSeconds() >= 0,
          "smallObjectCacheTtlSeconds cannot be negative");

      return options;
    }
  }
}
