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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;

/**
 * A two-tier {@link AnalyticsCache} that composes a fast L1 cache over a larger, slower L2 cache. A
 * lookup consults L1 first and, on a miss, falls back to L2; a value found in L2 is promoted into
 * L1 so subsequent lookups are served from the faster tier.
 *
 * <p>This combinator is agnostic to the concrete tiers. The typical arrangement pairs an in-memory
 * L1 (e.g. {@link AnalyticsCacheCaffeineImpl}) with a worker-level, file-based L2, but any two
 * {@link AnalyticsCache} instances may be combined.
 *
 * @param <K> The type of keys maintained by this cache.
 * @param <V> The type of mapped values.
 */
public final class AnalyticsCacheHybridImpl<K, V> implements AnalyticsCache<K, V> {

  private final AnalyticsCache<K, V> l1Cache;
  private final AnalyticsCache<K, V> l2Cache;

  /** Creates a hybrid cache with the given L1 (fast) and L2 (large) tiers. */
  public static <K, V> AnalyticsCacheHybridImpl<K, V> create(
      AnalyticsCache<K, V> l1Cache, AnalyticsCache<K, V> l2Cache) {
    return new AnalyticsCacheHybridImpl<>(l1Cache, l2Cache);
  }

  private AnalyticsCacheHybridImpl(AnalyticsCache<K, V> l1Cache, AnalyticsCache<K, V> l2Cache) {
    this.l1Cache = checkNotNull(l1Cache, "l1Cache cannot be null");
    this.l2Cache = checkNotNull(l2Cache, "l2Cache cannot be null");
  }

  /** {@inheritDoc} */
  @Override
  public Optional<V> get(K key) {
    checkNotNull(key, "key cannot be null");
    Optional<V> l1Value = l1Cache.get(key);
    if (l1Value.isPresent()) {
      return l1Value;
    }
    Optional<V> l2Value = l2Cache.get(key);
    l2Value.ifPresent(value -> l1Cache.put(key, value));
    return l2Value;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The load is driven by L1's atomic loader, whose mapping function delegates to L2's atomic
   * loader. As a result the {@code mappingFunction} is applied at most once per key across both
   * tiers, and a value loaded on an L1 miss is stored in L2 by L2's own loader before being cached
   * in L1.
   */
  @Override
  public <E extends Exception> V get(
      K key, ThrowingFunction<? super K, ? extends V, E> mappingFunction) throws E {
    checkNotNull(key, "key cannot be null");
    checkNotNull(mappingFunction, "mappingFunction cannot be null");
    return l1Cache.get(key, l1Key -> l2Cache.get(l1Key, mappingFunction));
  }

  /** {@inheritDoc} */
  @Override
  public void put(K key, V value) {
    checkNotNull(key, "key cannot be null");
    checkNotNull(value, "value cannot be null");
    l1Cache.put(key, value);
    l2Cache.put(key, value);
  }

  /** {@inheritDoc} */
  @Override
  public void invalidate(K key) {
    checkNotNull(key, "key cannot be null");
    l1Cache.invalidate(key);
    l2Cache.invalidate(key);
  }

  /** {@inheritDoc} */
  @Override
  public void invalidateAll() {
    l1Cache.invalidateAll();
    l2Cache.invalidateAll();
  }

  /** {@inheritDoc} Reports the L1 size, since L2 sizing may be expensive to compute. */
  @Override
  public long size() {
    return l1Cache.size();
  }

  /** {@inheritDoc} */
  @Override
  public void cleanUp() {
    l1Cache.cleanUp();
    l2Cache.cleanUp();
  }
}
