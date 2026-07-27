/*
 * Copyright 2025 Google LLC
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

import com.google.cloud.gcs.analyticscore.common.telemetry.Telemetry;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.WritableByteChannel;
import java.util.Map;
import java.util.function.Supplier;

public interface GcsFileSystem extends AutoCloseable {

  /**
   * Returns the file system for {@code properties}, shared JVM-wide with every other caller whose
   * properties carry the same authorization and produce the same options, creating it with {@code
   * credentials} if there is none yet.
   *
   * <p>Sharing is a security boundary: callers reaching one entry share cached object data, and
   * nothing verifies access at lookup time. Build {@code credentials} by switching on {@link
   * GcsAuthType#of} exhaustively and without a {@code default} branch, so that it and the key,
   * derived from the same classification, cannot disagree about which mechanism applies.
   *
   * <p>{@code credentials} is called only when a file system is created, never on a hit, so a
   * lookup never pays to mint credentials it would discard. Anything closeable they own is handed
   * over through {@link GcsCredentials} and closed with the file system, not by the caller.
   *
   * <p>The result is owned by the cache and must not be closed. Call this for each use rather than
   * holding it, so that an entry in use cannot expire underneath its user.
   *
   * @param properties the GCS properties, including the credential properties keyed on
   * @param propertyPrefix prefix the properties are read under, for example {@code "gcs."}
   * @param storagePrefix the storage location these properties authorize, part of the key
   * @param credentials the credentials for a new file system, with any resources to close with it
   */
  static GcsFileSystem getOrCreate(
      Map<String, String> properties,
      String propertyPrefix,
      String storagePrefix,
      Supplier<GcsCredentials> credentials) {
    return GcsFileSystemCache.getOrCreate(properties, propertyPrefix, storagePrefix, credentials);
  }

  /**
   * Closes and drops every shared file system. Reads in flight against one will fail, so this is
   * for tests and deliberate shutdown, not for reclaiming memory.
   */
  static void invalidateInstances() {
    GcsFileSystemCache.invalidateAll();
  }

  /**
   * Opens an object for reading.
   *
   * @param gcsFileInfo Contains information about a GCS File.
   * @param options Fine-grained read options for behaviors of retries, decryption, etc.
   * @return A channel for reading from the given object.
   * @throws FileNotFoundException if the given path does not exist.
   * @throws IOException if object exists but cannot be opened.
   */
  VectoredSeekableByteChannel open(GcsFileInfo gcsFileInfo, GcsReadOptions options)
      throws IOException;

  /**
   * Opens an object for reading.
   *
   * @param gcsItemId gcs object identifier.
   * @param options Fine-grained read options for behaviors of retries, decryption, etc.
   * @return A channel for reading from the given object.
   * @throws FileNotFoundException if the given path does not exist.
   * @throws IOException if object exists but cannot be opened.
   */
  VectoredSeekableByteChannel open(GcsItemId gcsItemId, GcsReadOptions options) throws IOException;

  /**
   * Gets Metadata about the given path item.
   *
   * @param path The path we want Metadata about.
   * @return Metadata about the given path item.
   */
  GcsFileInfo getFileInfo(URI path) throws IOException;

  /** Gets Metadata about the given gcs object represented by itemId. */
  GcsFileInfo getFileInfo(GcsItemId itemId) throws IOException;

  /** Retrieve the options that were used to create this GcsFileSystem. */
  GcsFileSystemOptions getFileSystemOptions();

  /** Retrieve the gcs client used to create this GcsFileSystem. */
  GcsClient getGcsClient();

  /** Retrieve the telemetry instance used by this file system. */
  Telemetry getTelemetry();

  /** Returns the cache manager used by this file system. */
  AnalyticsCacheManager getCacheManager();

  /** Close the file system. */
  @Override
  void close();

  /**
   * Creates a new GCS object and returns a WritableByteChannel for writing to it.
   *
   * @param itemId the identity of the GCS object to be created
   * @param options configuration options for controlling upload strategies and integrity checks
   * @return a channel for writing data to the newly created object
   * @throws IOException if an I/O error occurs during channel initialization or translation
   */
  WritableByteChannel create(GcsItemId itemId, GcsWriteOptions options) throws IOException;
}
