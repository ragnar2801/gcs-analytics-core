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
import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A JVM-wide cache of {@link GcsFileSystem} instances, keyed on the authorization the properties
 * carry ({@link GcsAuthType}) and the options the file system is built with. See {@link
 * GcsFileSystem#getOrCreate} for the contract callers rely on.
 *
 * <p>The cache owns what it holds and closes an entry when it expires or is invalidated. A key can
 * carry a credential and so must never be logged.
 */
final class GcsFileSystemCache {

  /** Releases file systems whose credentials are no longer in use, such as a replaced token. */
  private static final long EXPIRE_AFTER_ACCESS_MINUTES = 60;

  private static final Cache<String, GcsFileSystem> CACHE =
      Caffeine.newBuilder()
          .expireAfterAccess(EXPIRE_AFTER_ACCESS_MINUTES, TimeUnit.MINUTES)
          // Maintain on the calling thread, so a file system is closed before the removal returns.
          .executor(Runnable::run)
          .removalListener(
              (String key, GcsFileSystem fileSystem, RemovalCause cause) -> {
                if (fileSystem != null) {
                  fileSystem.close();
                }
              })
          .build();

  private GcsFileSystemCache() {}

  /** Implements {@link GcsFileSystem#getOrCreate}, which documents the contract. */
  static GcsFileSystem getOrCreate(
      Map<String, String> properties,
      String propertyPrefix,
      String storagePrefix,
      Supplier<GcsCredentials> credentials) {
    checkNotNull(properties, "properties cannot be null");
    checkNotNull(propertyPrefix, "propertyPrefix cannot be null");
    checkNotNull(storagePrefix, "storagePrefix cannot be null");
    checkNotNull(credentials, "credentials cannot be null");

    GcsFileSystemOptions options =
        GcsFileSystemOptions.createFromOptions(properties, propertyPrefix);
    // Options are part of the key: two callers with equal credentials can still disagree about the
    // endpoint and client configuration.
    String key = credentialScope(properties, propertyPrefix, storagePrefix) + "|" + options;

    GcsFileSystem fileSystem = CACHE.get(key, missed -> create(credentials, options));
    checkState(fileSystem != null, "credentials supplied no file system");

    return fileSystem;
  }

  /**
   * Builds the file system for a key the cache does not hold, taking ownership of whatever the
   * credentials own. Called at most once per key, and never on a hit.
   */
  private static GcsFileSystem create(
      Supplier<GcsCredentials> credentials, GcsFileSystemOptions options) {
    GcsCredentials resolved = credentials.get();
    checkState(resolved != null, "credentials supplied null");

    try {
      return resolved.credentials() == null
          ? new GcsFileSystemImpl(options, resolved.resources())
          : new GcsFileSystemImpl(resolved.credentials(), options, resolved.resources());
    } catch (RuntimeException | Error e) {
      // Ownership only transfers once the constructor returns, so a failed construction would
      // otherwise strand what the caller handed over with nothing left to close it.
      try {
        resolved.resources().close();
      } catch (IOException | RuntimeException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  /**
   * Returns an identifier for the authorization {@code properties} carry, keeping file systems with
   * different access from sharing cached object data. Values identifying equal access must produce
   * equal scopes and nothing else may: an over-specific scope only costs cache hits, while an
   * under-specific one lets a caller read data fetched with credentials it does not hold.
   */
  @VisibleForTesting
  static String credentialScope(
      Map<String, String> properties, String propertyPrefix, String storagePrefix) {
    GcsAuthType authType = GcsAuthType.of(properties, propertyPrefix);
    switch (authType) {
      case TOKEN:
        // The token is the grant itself, so equal tokens mean equal access however it was obtained.
        return storagePrefix
            + "|token:"
            + properties.get(propertyPrefix + GcsAuthType.OAUTH2_TOKEN_KEY);
      case NO_AUTH:
        return storagePrefix + "|no-auth";
      case IMPERSONATION:
        // Delegates and scopes change what the credential can do and who may mint it, so both are
        // part of the identity, not just the target service account.
        return storagePrefix
            + "|service-account:"
            + properties.get(propertyPrefix + GcsAuthType.IMPERSONATE_SERVICE_ACCOUNT_KEY)
            + "|delegates:"
            + properties.get(propertyPrefix + GcsAuthType.IMPERSONATE_DELEGATES_KEY)
            + "|scopes:"
            + properties.get(propertyPrefix + GcsAuthType.IMPERSONATE_SCOPES_KEY);
      case APPLICATION_DEFAULT:
        // Resolves to a single identity per process.
        return storagePrefix + "|application-default";
    }
    throw new IllegalStateException("Unhandled auth type: " + authType);
  }

  /** Closes and removes every cached file system. See {@link GcsFileSystem#invalidateInstances}. */
  static void invalidateAll() {
    CACHE.invalidateAll();
    CACHE.cleanUp();
  }

  @VisibleForTesting
  static long size() {
    CACHE.cleanUp();
    return CACHE.estimatedSize();
  }
}
