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

package com.google.cloud.gcs.analyticscore.common.cache;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalyticsCacheCaffeineImplTest {

  private AnalyticsCacheCaffeineImpl<String, String> cache;

  @BeforeEach
  void setUp() {
    cache = AnalyticsCacheCaffeineImpl.create(10, (key, value) -> 1);
  }

  @Test
  void create_nullWeigher_throwsException() {
    NullPointerException exception =
        assertThrows(NullPointerException.class, () -> AnalyticsCacheCaffeineImpl.create(10, null));
    assertThat(exception).hasMessageThat().contains("weigher cannot be null");
  }

  @Test
  void get_notPresent_returnsEmpty() {
    assertThat(cache.get("key1")).isEmpty();
  }

  @Test
  void get_present_returnsValue() {
    cache.put("key1", "value1");

    assertThat(cache.get("key1")).hasValue("value1");
  }

  @Test
  void get_withMappingFunction_notPresent_computesAndCachesValue() throws Exception {
    String key = "key1";
    AtomicInteger callCount = new AtomicInteger(0);

    String value =
        cache.get(
            key,
            keyToLoad -> {
              callCount.incrementAndGet();
              return "computed-" + keyToLoad;
            });
    String secondValue = cache.get(key, keyToLoad -> "should-not-happen");

    assertThat(value).isEqualTo("computed-key1");
    assertThat(callCount.get()).isEqualTo(1);
    assertThat(secondValue).isEqualTo("computed-key1");
  }

  @Test
  void get_withMappingFunction_returnsNull_throwsException() {
    assertThrows(NullPointerException.class, () -> cache.get("key1", k -> null));
  }

  @Test
  void get_withMappingFunction_throwsCheckedException_rethrowsException() {
    assertThrows(
        IOException.class,
        () ->
            cache.get(
                "key1",
                keyToLoad -> {
                  throw new IOException("test-exception");
                }));
  }

  @Test
  void invalidate_present_removesEntry() {
    cache.put("key1", "value1");

    cache.invalidate("key1");

    assertThat(cache.get("key1")).isEmpty();
  }

  @Test
  void invalidateAll_withEntries_clearsCache() {
    cache.put("key1", "value1");
    cache.put("key2", "value2");

    cache.invalidateAll();

    assertThat(cache.get("key1")).isEmpty();
    assertThat(cache.get("key2")).isEmpty();
  }

  @Test
  void size_withEntries_returnsCorrectCount() {
    cache.put("key1", "value1");
    cache.put("key2", "value2");

    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  void invalidateIf_removesOnlyMatchingKeys() {
    cache.put("scopeA:key1", "value1");
    cache.put("scopeA:key2", "value2");
    cache.put("scopeB:key1", "value3");

    cache.invalidateIf(key -> key.startsWith("scopeA:"));

    assertThat(cache.get("scopeA:key1")).isEmpty();
    assertThat(cache.get("scopeA:key2")).isEmpty();
    assertThat(cache.get("scopeB:key1")).hasValue("value3");
  }

  @Test
  void invalidateIf_nullPredicate_throwsException() {
    NullPointerException exception =
        assertThrows(NullPointerException.class, () -> cache.invalidateIf(null));
    assertThat(exception).hasMessageThat().contains("keyPredicate cannot be null");
  }

  @Test
  void create_withTtl_cachesValues() {
    AnalyticsCacheCaffeineImpl<String, String> ttlCache =
        AnalyticsCacheCaffeineImpl.create(10, (key, value) -> 1, 60);
    ttlCache.put("key1", "value1");

    assertThat(ttlCache.get("key1")).hasValue("value1");
  }

  @Test
  void create_negativeTtl_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> AnalyticsCacheCaffeineImpl.create(10, (key, value) -> 1, -1));
    assertThat(exception).hasMessageThat().contains("ttlSeconds cannot be negative");
  }
}
