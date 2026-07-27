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

import com.google.auth.Credentials;
import java.io.Closeable;
import javax.annotation.Nullable;

/**
 * The credentials a file system authenticates with, together with any resources that have to stay
 * open for as long as it uses them, such as a refresh handler holding an HTTP client.
 *
 * <p>A file system from {@link GcsFileSystem#getOrCreate} is shared and outlives the caller that
 * created it, so those resources are handed over here rather than kept by that caller, and are
 * closed with the file system.
 */
public final class GcsCredentials {

  private static final Closeable NO_RESOURCES = () -> {};

  @Nullable private final Credentials credentials;
  private final Closeable resources;

  private GcsCredentials(@Nullable Credentials credentials, Closeable resources) {
    this.credentials = credentials;
    this.resources = checkNotNull(resources, "resources cannot be null");
  }

  /** Credentials owning nothing that needs closing. */
  public static GcsCredentials of(Credentials credentials) {
    return new GcsCredentials(
        checkNotNull(credentials, "credentials cannot be null"), NO_RESOURCES);
  }

  /** Credentials that own {@code resources}, closed with the file system using them. */
  public static GcsCredentials of(Credentials credentials, Closeable resources) {
    return new GcsCredentials(checkNotNull(credentials, "credentials cannot be null"), resources);
  }

  /**
   * No explicit credentials: the file system resolves application default credentials for itself.
   */
  public static GcsCredentials applicationDefault() {
    return new GcsCredentials(null, NO_RESOURCES);
  }

  /** Returns the credentials, or null to use application default credentials. */
  @Nullable
  public Credentials credentials() {
    return credentials;
  }

  /** Returns the resources to close with the file system. */
  public Closeable resources() {
    return resources;
  }
}
