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

import com.google.common.annotations.VisibleForTesting;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JVM-wide cache of {@link GcsFileSystem} instances, so that callers reading the same data with
 * the same authorization share one GCS client, one read thread pool, and one set of object caches
 * instead of building a file system per caller.
 *
 * <p><b>Isolation is by key, not by permission check.</b> Callers supply a {@code credentialScope}
 * identifying the authorization they hold, and two callers share a file system only if their scope
 * and their {@link GcsFileSystemOptions} are equal. Cached object bytes therefore cannot reach a
 * caller whose scope differs. This cache cannot validate a scope: passing equal scopes for unequal
 * authorization would let one caller read another's data. Callers must derive the scope from
 * whatever identifies the credential they hold (a vended token, an impersonated service account
 * with its delegates and scopes, and so on), and when in doubt should make the scope more specific:
 * an over-specific scope only costs cache hits, while an under-specific one leaks data.
 *
 * <p>Instances are reference counted. {@link #acquire} adds a reference and {@link #release}
 * removes one; the shared file system is closed once the last reference is released. Callers must
 * therefore release exactly once per acquire, and must not call {@link GcsFileSystem#close()} on an
 * instance obtained from this cache -- that would close it for every other holder.
 */
public final class GcsFileSystemCache {

  private static final Logger LOG = LoggerFactory.getLogger(GcsFileSystemCache.class);

  private static final ConcurrentHashMap<Key, Entry> CACHE = new ConcurrentHashMap<>();

  private GcsFileSystemCache() {}

  /**
   * Returns the shared file system for {@code credentialScope} and {@code options}, creating it
   * with {@code factory} on first use, and adds a reference to it.
   *
   * <p>If {@code factory} throws, nothing is cached and the exception is propagated to the caller.
   *
   * @param credentialScope identifies the authorization the caller holds; callers with different
   *     access must pass different values
   * @param options the options the file system is (or was) created with
   * @param factory creates the file system on a cache miss
   */
  public static GcsFileSystem acquire(
      String credentialScope, GcsFileSystemOptions options, Supplier<GcsFileSystem> factory) {
    checkNotNull(credentialScope, "credentialScope cannot be null");
    checkNotNull(options, "options cannot be null");
    checkNotNull(factory, "factory cannot be null");

    // All refCount mutations happen inside compute() on the same key, so the map's per-key lock is
    // the only synchronization needed here.
    return CACHE.compute(
            new Key(credentialScope, options),
            (key, entry) -> {
              if (entry == null) {
                entry =
                    new Entry(checkNotNull(factory.get(), "factory returned a null file system"));
                LOG.debug("Created shared GcsFileSystem for credential scope {}", credentialScope);
              }
              entry.refCount++;
              return entry;
            })
        .fileSystem;
  }

  /**
   * Removes one reference to a file system obtained from {@link #acquire}, closing it if this was
   * the last reference.
   *
   * @return true if a reference was removed, false if the file system is not cached, in which case
   *     it is owned by the caller and closing it is up to them
   */
  public static boolean release(GcsFileSystem fileSystem) {
    if (fileSystem == null) {
      return false;
    }

    Key key = keyOf(fileSystem);
    if (key == null) {
      return false;
    }

    // Holds the instance to close, if this release drops the last reference. Closing happens
    // outside compute() so the map is not locked while the file system shuts down.
    GcsFileSystem[] toClose = new GcsFileSystem[1];
    CACHE.computeIfPresent(
        key,
        (cachedKey, entry) -> {
          if (--entry.refCount > 0) {
            return entry;
          }
          toClose[0] = entry.fileSystem;
          return null; // removes the entry
        });

    if (toClose[0] != null) {
      LOG.debug("Closing shared GcsFileSystem for credential scope {}", key.credentialScope);
      toClose[0].close();
    }

    return true;
  }

  private static Key keyOf(GcsFileSystem fileSystem) {
    for (Map.Entry<Key, Entry> entry : CACHE.entrySet()) {
      if (entry.getValue().fileSystem == fileSystem) {
        return entry.getKey();
      }
    }

    return null;
  }

  /** Returns the number of cached file systems. */
  @VisibleForTesting
  static int size() {
    return CACHE.size();
  }

  /** Closes and removes every cached file system, ignoring reference counts. */
  @VisibleForTesting
  static void closeAll() {
    for (Key key : CACHE.keySet()) {
      Entry entry = CACHE.remove(key);
      if (entry != null) {
        entry.fileSystem.close();
      }
    }
  }

  private static final class Entry {
    private final GcsFileSystem fileSystem;

    // Guarded by CACHE.compute()/computeIfPresent() on this entry's key.
    private int refCount;

    private Entry(GcsFileSystem fileSystem) {
      this.fileSystem = fileSystem;
    }
  }

  private static final class Key {
    private final String credentialScope;

    // GcsFileSystemOptions is an AutoValue type, so this compares by value.
    private final GcsFileSystemOptions options;

    private Key(String credentialScope, GcsFileSystemOptions options) {
      this.credentialScope = credentialScope;
      this.options = options;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof Key)) {
        return false;
      }

      Key that = (Key) other;
      return credentialScope.equals(that.credentialScope) && options.equals(that.options);
    }

    @Override
    public int hashCode() {
      return Objects.hash(credentialScope, options);
    }
  }
}
