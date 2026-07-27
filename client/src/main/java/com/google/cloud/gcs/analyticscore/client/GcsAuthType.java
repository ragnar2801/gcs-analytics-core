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
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

/**
 * The credential mechanism a set of GCS properties selects, and the single place the precedence
 * between mechanisms is defined. {@link GcsFileSystemCache} derives the credential half of its key
 * from it, and callers build their credentials by switching on it, so the two cannot disagree.
 *
 * <p>Switch over this enum exhaustively and without a {@code default} branch, so that a mechanism
 * added here becomes a compile error rather than two grants silently sharing a cache key.
 */
public enum GcsAuthType {
  /** An OAuth2 access token, supplied directly or vended by a catalog. */
  TOKEN,
  /** No credentials at all, for emulators and testing. */
  NO_AUTH,
  /** A service account impersonated from the ambient credentials. */
  IMPERSONATION,
  /** Application default credentials, which resolve to a single identity per process. */
  APPLICATION_DEFAULT;

  static final String OAUTH2_TOKEN_KEY = "oauth2.token";
  static final String NO_AUTH_KEY = "no-auth";
  static final String IMPERSONATE_SERVICE_ACCOUNT_KEY = "impersonate.service-account";
  static final String IMPERSONATE_DELEGATES_KEY = "impersonate.delegates";
  static final String IMPERSONATE_SCOPES_KEY = "impersonate.scopes";

  /**
   * Returns the mechanism {@code properties} selects, reading keys under {@code propertyPrefix}
   * (for example {@code "gcs."}). An explicit token wins over no-auth, which wins over
   * impersonation; a configuration setting none of them uses application default credentials.
   */
  public static GcsAuthType of(Map<String, String> properties, String propertyPrefix) {
    checkNotNull(properties, "properties cannot be null");
    checkArgument(propertyPrefix != null, "propertyPrefix cannot be null");

    if (properties.get(propertyPrefix + OAUTH2_TOKEN_KEY) != null) {
      return TOKEN;
    } else if (Boolean.parseBoolean(properties.get(propertyPrefix + NO_AUTH_KEY))) {
      return NO_AUTH;
    } else if (properties.get(propertyPrefix + IMPERSONATE_SERVICE_ACCOUNT_KEY) != null) {
      return IMPERSONATION;
    } else {
      return APPLICATION_DEFAULT;
    }
  }
}
