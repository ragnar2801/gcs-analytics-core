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

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GcsCacheOptionsTest {

  private static final long KB = 1024L;
  private static final long MB = 1024L * KB;

  @Test
  void build_defaultValues_succeeds() {
    GcsCacheOptions options = GcsCacheOptions.builder().build();

    assertThat(options.isFooterCacheEnabled()).isFalse();
    assertThat(options.getFooterCacheMaxSizeBytes()).isEqualTo(100 * MB);
    assertThat(options.isSmallObjectCacheEnabled()).isFalse();
    assertThat(options.getSmallObjectCacheMaxSizeBytes()).isEqualTo(200 * MB);
  }

  @Test
  void build_disabledCacheNonPositiveSizeBytes_succeeds() {
    GcsCacheOptions options =
        GcsCacheOptions.builder()
            .setFooterCacheEnabled(false)
            .setFooterCacheMaxSizeBytes(0)
            .setSmallObjectCacheEnabled(false)
            .setSmallObjectCacheMaxSizeBytes(0)
            .build();

    assertThat(options.isFooterCacheEnabled()).isFalse();
    assertThat(options.getFooterCacheMaxSizeBytes()).isEqualTo(0);
    assertThat(options.isSmallObjectCacheEnabled()).isFalse();
    assertThat(options.getSmallObjectCacheMaxSizeBytes()).isEqualTo(0);
  }

  @Test
  void build_enabledCacheZeroSizeBytes_throwsException() {
    GcsCacheOptions.Builder builder =
        GcsCacheOptions.builder().setFooterCacheEnabled(true).setFooterCacheMaxSizeBytes(0);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void build_enabledCacheNegativeSizeBytes_throwsException() {
    GcsCacheOptions.Builder builder =
        GcsCacheOptions.builder().setFooterCacheEnabled(true).setFooterCacheMaxSizeBytes(-1);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  void createFromOptions_withAllOptions_succeeds() {
    boolean footerCacheEnabled = false;
    long footerCacheMaxSizeBytes = 50 * MB;
    boolean smallObjectCacheEnabled = true;
    long smallObjectCacheMaxSizeBytes = 100 * MB;

    Map<String, String> map = new HashMap<>();
    map.put("gcs." + GcsCacheOptions.FOOTER_CACHE_ENABLED_KEY, String.valueOf(footerCacheEnabled));
    map.put(
        "gcs." + GcsCacheOptions.FOOTER_CACHE_MAX_SIZE_BYTES_KEY,
        String.valueOf(footerCacheMaxSizeBytes));
    map.put(
        "gcs." + GcsCacheOptions.SMALL_FILE_CACHE_ENABLED_KEY,
        String.valueOf(smallObjectCacheEnabled));
    map.put(
        "gcs." + GcsCacheOptions.SMALL_FILE_CACHE_MAX_SIZE_BYTES_KEY,
        String.valueOf(smallObjectCacheMaxSizeBytes));

    GcsCacheOptions options = GcsCacheOptions.createFromOptions(map, "gcs.");

    assertThat(options.isFooterCacheEnabled()).isEqualTo(footerCacheEnabled);
    assertThat(options.getFooterCacheMaxSizeBytes()).isEqualTo(footerCacheMaxSizeBytes);
    assertThat(options.isSmallObjectCacheEnabled()).isEqualTo(smallObjectCacheEnabled);
    assertThat(options.getSmallObjectCacheMaxSizeBytes()).isEqualTo(smallObjectCacheMaxSizeBytes);
  }

  @Test
  void createFromOptions_withEmptyOptions_returnsDefaults() {
    Map<String, String> map = new HashMap<>();

    GcsCacheOptions options = GcsCacheOptions.createFromOptions(map, "gcs.");

    assertThat(options.isFooterCacheEnabled()).isFalse();
    assertThat(options.getFooterCacheMaxSizeBytes()).isEqualTo(100 * MB);
    assertThat(options.isSmallObjectCacheEnabled()).isFalse();
    assertThat(options.getSmallObjectCacheMaxSizeBytes()).isEqualTo(200 * MB);
  }

  @Test
  void createFromOptions_malformedInteger_throwsNumberFormatException() {
    Map<String, String> map = new HashMap<>();
    map.put("gcs." + GcsCacheOptions.FOOTER_CACHE_MAX_SIZE_BYTES_KEY, "not-a-number");

    assertThrows(NumberFormatException.class, () -> GcsCacheOptions.createFromOptions(map, "gcs."));
  }

  @Test
  void build_defaultValues_sharedCacheDisabledNoTtlNoScope() {
    GcsCacheOptions options = GcsCacheOptions.builder().build();

    assertThat(options.isSharedCacheEnabled()).isFalse();
    assertThat(options.getFooterCacheTtlSeconds()).isEqualTo(0);
    assertThat(options.getSmallObjectCacheTtlSeconds()).isEqualTo(0);
    assertThat(options.getCacheScope()).isEmpty();
  }

  @Test
  void createFromOptions_withTtlAndSharedFlag_succeeds() {
    Map<String, String> map = new HashMap<>();
    map.put("gcs." + GcsCacheOptions.FOOTER_CACHE_TTL_SECONDS_KEY, "300");
    map.put("gcs." + GcsCacheOptions.SMALL_FILE_CACHE_TTL_SECONDS_KEY, "600");
    map.put("gcs." + GcsCacheOptions.SHARED_CACHE_ENABLED_KEY, "true");

    GcsCacheOptions options = GcsCacheOptions.createFromOptions(map, "gcs.");

    assertThat(options.getFooterCacheTtlSeconds()).isEqualTo(300);
    assertThat(options.getSmallObjectCacheTtlSeconds()).isEqualTo(600);
    assertThat(options.isSharedCacheEnabled()).isTrue();
  }

  @Test
  void createFromOptions_cacheScopeInPropertyMap_isIgnored() {
    // The scope is a security boundary and must never be honored from the caller-controlled map;
    // a caller could otherwise set another principal's scope to read their cached bytes.
    Map<String, String> map = new HashMap<>();
    map.put("gcs.analytics-core.cache.scope", "gs://someone-elses/prefix/");

    GcsCacheOptions options = GcsCacheOptions.createFromOptions(map, "gcs.");

    assertThat(options.getCacheScope()).isEmpty();
  }

  @Test
  void setCacheScope_setsScopeProgrammatically() {
    GcsCacheOptions options = GcsCacheOptions.builder().setCacheScope("gs://bucket/table/").build();

    assertThat(options.getCacheScope()).hasValue("gs://bucket/table/");
  }

  @Test
  void build_negativeTtl_throwsException() {
    GcsCacheOptions.Builder builder =
        GcsCacheOptions.builder().setFooterCacheEnabled(true).setFooterCacheTtlSeconds(-1);

    assertThrows(IllegalArgumentException.class, builder::build);
  }
}
