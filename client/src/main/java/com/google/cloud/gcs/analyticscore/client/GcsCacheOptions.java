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
import java.util.concurrent.TimeUnit;

/** Configuration options for the GCS caching layer. */
@AutoValue
public abstract class GcsCacheOptions {

  static final String FOOTER_CACHE_ENABLED_KEY = "analytics-core.footer.cache.enabled";
  static final String FOOTER_CACHE_MAX_SIZE_BYTES_KEY =
      "analytics-core.footer.cache.max-size-bytes";
  static final String SMALL_FILE_CACHE_ENABLED_KEY = "analytics-core.small-file.cache.enabled";
  static final String SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY =
      "analytics-core.small-file.cache.max-size-bytes";
  static final String DISK_CACHE_ENABLED_KEY = "analytics-core.cache.disk.enabled";
  static final String DISK_CACHE_DIRECTORY_KEY = "analytics-core.cache.disk.directory";
  static final String DISK_CACHE_MAX_SIZE_BYTES_KEY = "analytics-core.cache.disk.max-size-bytes";
  static final String DISK_CACHE_TTL_MILLIS_KEY = "analytics-core.cache.disk.ttl-millis";
  static final String DISK_CACHE_HIGH_WATERMARK_KEY =
      "analytics-core.cache.disk.eviction.high-watermark";
  static final String DISK_CACHE_LOW_WATERMARK_KEY =
      "analytics-core.cache.disk.eviction.low-watermark";

  private static final long KB = 1024L;
  private static final long MB = 1024L * KB;
  private static final long GB = 1024L * MB;

  private static final boolean DEFAULT_FOOTER_CACHE_ENABLED = false;
  private static final long DEFAULT_FOOTER_CACHE_MAX_SIZE_BYTES = 100 * MB;
  private static final boolean DEFAULT_SMALL_OBJECT_CACHE_ENABLED = false;
  private static final long DEFAULT_SMALL_OBJECT_CACHE_MAX_SIZE_BYTES = 200 * MB;
  private static final boolean DEFAULT_DISK_CACHE_ENABLED = false;
  private static final long DEFAULT_DISK_CACHE_MAX_SIZE_BYTES = 10 * GB;
  private static final long DEFAULT_DISK_CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);
  private static final double DEFAULT_DISK_CACHE_HIGH_WATERMARK = 0.95;
  private static final double DEFAULT_DISK_CACHE_LOW_WATERMARK = 0.85;

  /** Returns whether the Parquet footer cache is enabled. */
  public abstract boolean isFooterCacheEnabled();

  /** Returns the maximum capacity (in bytes) to hold in the Parquet footer cache. */
  public abstract long getFooterCacheMaxSizeBytes();

  /** Returns the maximum capacity (in bytes) to hold in the small object cache. */
  /** Returns whether the small object cache is enabled. */
  public abstract boolean isSmallObjectCacheEnabled();

  /** Returns the maximum capacity (in bytes) to hold in the small object cache. */
  public abstract long getSmallObjectCacheMaxSizeBytes();

  /** Returns whether the worker-level (host-shared) disk cache is enabled. */
  public abstract boolean isDiskCacheEnabled();

  /** Returns the local directory backing the disk cache, shared by all executors on the host. */
  public abstract Optional<String> getDiskCacheDirectory();

  /** Returns the maximum total size (in bytes) of the disk cache. */
  public abstract long getDiskCacheMaxSizeBytes();

  /**
   * Returns the time-to-live (in milliseconds) of a disk cache entry; {@code 0} disables TTL-based
   * expiry.
   */
  public abstract long getDiskCacheTtlMillis();

  /** Returns the disk usage fraction above which background eviction starts. */
  public abstract double getDiskCacheEvictionHighWatermark();

  /** Returns the disk usage fraction down to which background eviction proceeds. */
  public abstract double getDiskCacheEvictionLowWatermark();

  /**
   * Returns a builder for {@link GcsCacheOptions} with the same property values as this instance.
   */
  public abstract Builder toBuilder();

  /** Returns a new builder for {@link GcsCacheOptions} with default values. */
  public static Builder builder() {
    return new AutoValue_GcsCacheOptions.Builder()
        .setFooterCacheEnabled(DEFAULT_FOOTER_CACHE_ENABLED)
        .setFooterCacheMaxSizeBytes(DEFAULT_FOOTER_CACHE_MAX_SIZE_BYTES)
        .setSmallObjectCacheEnabled(DEFAULT_SMALL_OBJECT_CACHE_ENABLED)
        .setSmallObjectCacheMaxSizeBytes(DEFAULT_SMALL_OBJECT_CACHE_MAX_SIZE_BYTES)
        .setDiskCacheEnabled(DEFAULT_DISK_CACHE_ENABLED)
        .setDiskCacheMaxSizeBytes(DEFAULT_DISK_CACHE_MAX_SIZE_BYTES)
        .setDiskCacheTtlMillis(DEFAULT_DISK_CACHE_TTL_MILLIS)
        .setDiskCacheEvictionHighWatermark(DEFAULT_DISK_CACHE_HIGH_WATERMARK)
        .setDiskCacheEvictionLowWatermark(DEFAULT_DISK_CACHE_LOW_WATERMARK);
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
    if (analyticsCoreOptions.containsKey(prefix + SMALL_FILE_CACHE_ENABLED_KEY)) {
      optionsBuilder.setSmallObjectCacheEnabled(
          Boolean.parseBoolean(analyticsCoreOptions.get(prefix + SMALL_FILE_CACHE_ENABLED_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY)) {
      optionsBuilder.setSmallObjectCacheMaxSizeBytes(
          Long.parseLong(analyticsCoreOptions.get(prefix + SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_ENABLED_KEY)) {
      optionsBuilder.setDiskCacheEnabled(
          Boolean.parseBoolean(analyticsCoreOptions.get(prefix + DISK_CACHE_ENABLED_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_DIRECTORY_KEY)) {
      optionsBuilder.setDiskCacheDirectory(
          analyticsCoreOptions.get(prefix + DISK_CACHE_DIRECTORY_KEY));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_MAX_SIZE_BYTES_KEY)) {
      optionsBuilder.setDiskCacheMaxSizeBytes(
          Long.parseLong(analyticsCoreOptions.get(prefix + DISK_CACHE_MAX_SIZE_BYTES_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_TTL_MILLIS_KEY)) {
      optionsBuilder.setDiskCacheTtlMillis(
          Long.parseLong(analyticsCoreOptions.get(prefix + DISK_CACHE_TTL_MILLIS_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_HIGH_WATERMARK_KEY)) {
      optionsBuilder.setDiskCacheEvictionHighWatermark(
          Double.parseDouble(analyticsCoreOptions.get(prefix + DISK_CACHE_HIGH_WATERMARK_KEY)));
    }
    if (analyticsCoreOptions.containsKey(prefix + DISK_CACHE_LOW_WATERMARK_KEY)) {
      optionsBuilder.setDiskCacheEvictionLowWatermark(
          Double.parseDouble(analyticsCoreOptions.get(prefix + DISK_CACHE_LOW_WATERMARK_KEY)));
    }

    return optionsBuilder.build();
  }

  /** Builder for {@link GcsCacheOptions}. */
  @AutoValue.Builder
  public abstract static class Builder {
    /** Sets whether the Parquet footer cache is enabled. */
    public abstract Builder setFooterCacheEnabled(boolean footerCacheEnabled);

    /** Sets the maximum capacity (in bytes) to hold in the Parquet footer cache. */
    public abstract Builder setFooterCacheMaxSizeBytes(long footerCacheMaxSizeBytes);

    /** Sets the maximum capacity (in bytes) to hold in the small object cache. */
    /** Sets whether the small object cache is enabled. */
    public abstract Builder setSmallObjectCacheEnabled(boolean smallObjectCacheEnabled);

    /** Sets the maximum capacity (in bytes) to hold in the small object cache. */
    public abstract Builder setSmallObjectCacheMaxSizeBytes(long smallObjectCacheMaxSizeBytes);

    /** Sets whether the worker-level (host-shared) disk cache is enabled. */
    public abstract Builder setDiskCacheEnabled(boolean diskCacheEnabled);

    /** Sets the local directory backing the disk cache. */
    public abstract Builder setDiskCacheDirectory(String diskCacheDirectory);

    /** Sets the maximum total size (in bytes) of the disk cache. */
    public abstract Builder setDiskCacheMaxSizeBytes(long diskCacheMaxSizeBytes);

    /** Sets the time-to-live (in milliseconds) of a disk cache entry; {@code 0} disables TTL. */
    public abstract Builder setDiskCacheTtlMillis(long diskCacheTtlMillis);

    /** Sets the disk usage fraction above which background eviction starts. */
    public abstract Builder setDiskCacheEvictionHighWatermark(
        double diskCacheEvictionHighWatermark);

    /** Sets the disk usage fraction down to which background eviction proceeds. */
    public abstract Builder setDiskCacheEvictionLowWatermark(double diskCacheEvictionLowWatermark);

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
      if (options.isDiskCacheEnabled()) {
        checkArgument(
            options.getDiskCacheDirectory().filter(directory -> !directory.isEmpty()).isPresent(),
            "diskCacheDirectory must be set when diskCacheEnabled is true");
        checkArgument(
            options.getDiskCacheMaxSizeBytes() > 0,
            "diskCacheMaxSizeBytes must be positive when diskCacheEnabled is true");
        checkArgument(
            options.getDiskCacheTtlMillis() >= 0, "diskCacheTtlMillis cannot be negative");
        checkArgument(
            options.getDiskCacheEvictionLowWatermark() > 0
                && options.getDiskCacheEvictionLowWatermark()
                    < options.getDiskCacheEvictionHighWatermark()
                && options.getDiskCacheEvictionHighWatermark() <= 1.0,
            "disk cache watermarks must satisfy 0 < lowWatermark < highWatermark <= 1");
      }

      return options;
    }
  }
}
