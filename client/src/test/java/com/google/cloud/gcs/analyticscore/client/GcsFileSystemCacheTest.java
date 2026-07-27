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

import com.google.cloud.NoCredentials;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GcsFileSystemCacheTest {

  private static final String PROPERTY_PREFIX = "gcs.";
  private static final String STORAGE_PREFIX = "gs://bucket";
  private static final Map<String, String> NO_AUTH_PROPERTIES =
      ImmutableMap.of("gcs.no-auth", "true");

  @BeforeEach
  void setUp() {
    GcsFileSystemCache.invalidateAll();
  }

  @AfterEach
  void tearDown() {
    GcsFileSystemCache.invalidateAll();
  }

  @Test
  void getOrCreate_samePropertiesTwice_sharesOneFileSystem() {
    AtomicInteger credentialBuilds = new AtomicInteger();

    GcsFileSystem fileSystem = getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);
    GcsFileSystem sameProperties = getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);

    assertThat(fileSystem).isSameInstanceAs(sameProperties);
    assertThat(GcsFileSystemCache.size()).isEqualTo(1);
  }

  @Test
  void getOrCreate_cacheHit_doesNotBuildCredentials() {
    AtomicInteger credentialBuilds = new AtomicInteger();
    getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);

    getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);
    getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);

    // credentials can cost a network round trip to mint, so a hit must never pay for them
    assertThat(credentialBuilds.get()).isEqualTo(1);
  }

  @Test
  void getOrCreate_differingCredentials_doNotShare() {
    AtomicInteger credentialBuilds = new AtomicInteger();

    GcsFileSystem noAuth = getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);
    GcsFileSystem token =
        getOrCreate(ImmutableMap.of("gcs.oauth2.token", "token"), credentialBuilds);
    GcsFileSystem otherToken =
        getOrCreate(ImmutableMap.of("gcs.oauth2.token", "other-token"), credentialBuilds);

    assertThat(noAuth).isNotSameInstanceAs(token);
    assertThat(token).isNotSameInstanceAs(otherToken);
    assertThat(credentialBuilds.get()).isEqualTo(3);
  }

  @Test
  void getOrCreate_differingOptions_doNotShare() {
    AtomicInteger credentialBuilds = new AtomicInteger();
    Map<String, String> otherHost =
        ImmutableMap.of("gcs.no-auth", "true", "gcs.service.host", "example.com");

    assertThat(getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds))
        .isNotSameInstanceAs(getOrCreate(otherHost, credentialBuilds));
  }

  @Test
  void getOrCreate_differingStoragePrefix_doesNotShare() {
    GcsFileSystem bucket =
        GcsFileSystemCache.getOrCreate(
            NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, GcsFileSystemCacheTest::noAuth);
    GcsFileSystem otherBucket =
        GcsFileSystemCache.getOrCreate(
            NO_AUTH_PROPERTIES,
            PROPERTY_PREFIX,
            "gs://other-bucket",
            GcsFileSystemCacheTest::noAuth);

    assertThat(bucket).isNotSameInstanceAs(otherBucket);
  }

  @Test
  void getOrCreate_applicationDefaultCredentials_buildsFileSystem() {
    GcsFileSystem fileSystem =
        GcsFileSystemCache.getOrCreate(
            ImmutableMap.of("gcs.project-id", "myProject"),
            PROPERTY_PREFIX,
            STORAGE_PREFIX,
            GcsCredentials::applicationDefault);

    assertThat(fileSystem).isNotNull();
    assertThat(fileSystem.getGcsClient()).isNotNull();
  }

  @Test
  void getOrCreate_throwingCredentials_cachesNothingAndPropagates() {
    RuntimeException failure = new RuntimeException("cannot mint credentials");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                GcsFileSystemCache.getOrCreate(
                    NO_AUTH_PROPERTIES,
                    PROPERTY_PREFIX,
                    STORAGE_PREFIX,
                    () -> {
                      throw failure;
                    }));

    assertThat(thrown).isSameInstanceAs(failure);
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  void getOrCreate_credentialsSupplyingNull_throws() {
    assertThrows(
        IllegalStateException.class,
        () ->
            GcsFileSystemCache.getOrCreate(
                NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, () -> null));
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  void getOrCreate_whileCached_doesNotCloseCredentialResources() {
    AtomicBoolean credentialResourcesClosed = new AtomicBoolean();

    GcsFileSystemCache.getOrCreate(
        NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, closing(credentialResourcesClosed));
    GcsFileSystemCache.getOrCreate(
        NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, closing(credentialResourcesClosed));

    assertThat(credentialResourcesClosed.get()).isFalse();
  }

  @Test
  void getOrCreate_whenFileSystemCannotBeBuilt_closesCredentialResources() {
    AtomicBoolean credentialResourcesClosed = new AtomicBoolean();
    // an unsupported upload type fails while the client is being constructed, after the caller has
    // already handed over the resources its credentials own
    Map<String, String> journaling =
        ImmutableMap.of("gcs.no-auth", "true", "gcs.channel.write.upload-type", "journaling");

    assertThrows(
        RuntimeException.class,
        () ->
            GcsFileSystemCache.getOrCreate(
                journaling, PROPERTY_PREFIX, STORAGE_PREFIX, closing(credentialResourcesClosed)));

    assertThat(credentialResourcesClosed.get()).isTrue();
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  void getOrCreate_nullArgument_throws() {
    assertThrows(
        NullPointerException.class,
        () ->
            GcsFileSystemCache.getOrCreate(
                null, PROPERTY_PREFIX, STORAGE_PREFIX, GcsFileSystemCacheTest::noAuth));
    assertThrows(
        NullPointerException.class,
        () ->
            GcsFileSystemCache.getOrCreate(
                NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, null));
  }

  @Test
  void invalidateAll_closesCredentialResources() {
    AtomicBoolean credentialResourcesClosed = new AtomicBoolean();
    GcsFileSystemCache.getOrCreate(
        NO_AUTH_PROPERTIES, PROPERTY_PREFIX, STORAGE_PREFIX, closing(credentialResourcesClosed));

    GcsFileSystemCache.invalidateAll();

    assertThat(credentialResourcesClosed.get()).isTrue();
  }

  @Test
  void invalidateAll_thenGetOrCreate_createsAgain() {
    AtomicInteger credentialBuilds = new AtomicInteger();
    GcsFileSystem fileSystem = getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);

    GcsFileSystemCache.invalidateAll();
    GcsFileSystem recreated = getOrCreate(NO_AUTH_PROPERTIES, credentialBuilds);

    assertThat(recreated).isNotSameInstanceAs(fileSystem);
    assertThat(credentialBuilds.get()).isEqualTo(2);
  }

  @Test
  void credentialScope_equalCredentials_areEqual() {
    assertThat(scope(ImmutableMap.of("gcs.oauth2.token", "token")))
        .isEqualTo(scope(ImmutableMap.of("gcs.oauth2.token", "token")));
    assertThat(scope(ImmutableMap.of("gcs.no-auth", "true")))
        .isEqualTo(scope(ImmutableMap.of("gcs.no-auth", "true")));
    // application default resolves to one identity per process, whatever else differs
    assertThat(scope(ImmutableMap.of()))
        .isEqualTo(scope(ImmutableMap.of("gcs.project-id", "myProject")));
  }

  @Test
  void credentialScope_differingCredentials_areNotEqual() {
    String token = scope(ImmutableMap.of("gcs.oauth2.token", "token"));

    assertThat(token).isNotEqualTo(scope(ImmutableMap.of("gcs.oauth2.token", "other-token")));
    assertThat(token).isNotEqualTo(scope(ImmutableMap.of("gcs.no-auth", "true")));
    assertThat(token).isNotEqualTo(scope(ImmutableMap.of()));
    assertThat(scope(ImmutableMap.of("gcs.no-auth", "true")))
        .isNotEqualTo(scope(ImmutableMap.of()));
  }

  @Test
  void credentialScope_impersonation_includesDelegatesAndScopes() {
    String base = scope(ImmutableMap.of("gcs.impersonate.service-account", "sa@project.iam.test"));

    assertThat(base)
        .isNotEqualTo(
            scope(ImmutableMap.of("gcs.impersonate.service-account", "other@project.iam.test")));
    assertThat(base)
        .isNotEqualTo(
            scope(
                ImmutableMap.of(
                    "gcs.impersonate.service-account", "sa@project.iam.test",
                    "gcs.impersonate.delegates", "delegate@project.iam.test")));
    assertThat(base)
        .isNotEqualTo(
            scope(
                ImmutableMap.of(
                    "gcs.impersonate.service-account", "sa@project.iam.test",
                    "gcs.impersonate.scopes", "devstorage.read_only")));
  }

  @Test
  void credentialScope_differingStoragePrefix_isNotEqual() {
    Map<String, String> properties = ImmutableMap.of("gcs.oauth2.token", "token");

    assertThat(GcsFileSystemCache.credentialScope(properties, PROPERTY_PREFIX, STORAGE_PREFIX))
        .isNotEqualTo(
            GcsFileSystemCache.credentialScope(properties, PROPERTY_PREFIX, "gs://other-bucket"));
  }

  private static GcsFileSystem getOrCreate(
      Map<String, String> properties, AtomicInteger credentialBuilds) {
    return GcsFileSystemCache.getOrCreate(
        properties,
        PROPERTY_PREFIX,
        STORAGE_PREFIX,
        () -> {
          credentialBuilds.incrementAndGet();
          return noAuth();
        });
  }

  private static Supplier<GcsCredentials> closing(AtomicBoolean closed) {
    return () -> GcsCredentials.of(NoCredentials.getInstance(), () -> closed.set(true));
  }

  private static GcsCredentials noAuth() {
    return GcsCredentials.of(NoCredentials.getInstance());
  }

  private static String scope(Map<String, String> properties) {
    return GcsFileSystemCache.credentialScope(properties, PROPERTY_PREFIX, STORAGE_PREFIX);
  }
}
