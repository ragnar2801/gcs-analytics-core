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

import com.google.auto.value.AutoValue;

/**
 * Composite key for the footer and small-object caches.
 *
 * <p>When a cache is shared across {@link
 * com.google.cloud.gcs.analyticscore.client.AnalyticsCacheManager} instances (for example, a
 * JVM-wide cache reused across short-lived file systems), keying on {@link GcsItemId} alone would
 * let one caller be served bytes that were loaded under a different caller's credentials. The
 * {@code scope} partitions the cache by the authorization boundary that the bytes were read under,
 * so a cache hit can only ever return data that the requesting scope was already entitled to read.
 * Two callers share a cache entry only when they present the same {@code scope}.
 *
 * <p>The {@code scope} is an opaque authorization-boundary token supplied by the caller (for
 * example, the object-name prefix that a vended credential is scoped to). It must be derived from
 * an authority's grant, never inferred from the paths a caller has managed to read.
 */
@AutoValue
public abstract class CacheKey {

  /** The authorization-boundary token that partitions the shared cache. */
  public abstract String getScope();

  /** The identifier of the cached GCS object. */
  public abstract GcsItemId getItemId();

  /** Creates a {@link CacheKey} for the given {@code scope} and {@code itemId}. */
  public static CacheKey create(String scope, GcsItemId itemId) {
    return new AutoValue_CacheKey(scope, itemId);
  }
}
