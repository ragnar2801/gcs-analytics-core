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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

class GcsAuthTypeTest {

  private static final String PREFIX = "gcs.";

  @Test
  void of_token_returnsToken() {
    assertThat(GcsAuthType.of(ImmutableMap.of("gcs.oauth2.token", "token"), PREFIX))
        .isEqualTo(GcsAuthType.TOKEN);
  }

  @Test
  void of_noAuth_returnsNoAuth() {
    assertThat(GcsAuthType.of(ImmutableMap.of("gcs.no-auth", "true"), PREFIX))
        .isEqualTo(GcsAuthType.NO_AUTH);
  }

  @Test
  void of_noAuthFalse_returnsApplicationDefault() {
    assertThat(GcsAuthType.of(ImmutableMap.of("gcs.no-auth", "false"), PREFIX))
        .isEqualTo(GcsAuthType.APPLICATION_DEFAULT);
  }

  @Test
  void of_impersonation_returnsImpersonation() {
    assertThat(
            GcsAuthType.of(
                ImmutableMap.of("gcs.impersonate.service-account", "sa@project.iam.test"), PREFIX))
        .isEqualTo(GcsAuthType.IMPERSONATION);
  }

  @Test
  void of_noCredentialProperties_returnsApplicationDefault() {
    assertThat(GcsAuthType.of(ImmutableMap.of("gcs.project-id", "myProject"), PREFIX))
        .isEqualTo(GcsAuthType.APPLICATION_DEFAULT);
  }

  @Test
  void of_tokenWithImpersonation_prefersToken() {
    assertThat(
            GcsAuthType.of(
                ImmutableMap.of(
                    "gcs.oauth2.token",
                    "token",
                    "gcs.impersonate.service-account",
                    "sa@project.iam.test"),
                PREFIX))
        .isEqualTo(GcsAuthType.TOKEN);
  }

  @Test
  void of_noAuthWithImpersonation_prefersNoAuth() {
    assertThat(
            GcsAuthType.of(
                ImmutableMap.of(
                    "gcs.no-auth",
                    "true",
                    "gcs.impersonate.service-account",
                    "sa@project.iam.test"),
                PREFIX))
        .isEqualTo(GcsAuthType.NO_AUTH);
  }

  @Test
  void of_otherPrefix_readsThatPrefix() {
    assertThat(GcsAuthType.of(ImmutableMap.of("fs.gs.oauth2.token", "token"), "fs.gs."))
        .isEqualTo(GcsAuthType.TOKEN);
    assertThat(GcsAuthType.of(ImmutableMap.of("gcs.oauth2.token", "token"), "fs.gs."))
        .isEqualTo(GcsAuthType.APPLICATION_DEFAULT);
  }

  @Test
  void of_nullArgument_throws() {
    assertThrows(NullPointerException.class, () -> GcsAuthType.of(null, PREFIX));
    assertThrows(IllegalArgumentException.class, () -> GcsAuthType.of(ImmutableMap.of(), null));
  }
}
