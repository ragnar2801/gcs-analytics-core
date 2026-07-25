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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class GcsFileSystemCacheTest {

  private static final GcsFileSystemOptions OPTIONS =
      GcsFileSystemOptions.createFromOptions(ImmutableMap.of(), "gcs.");
  private static final GcsFileSystemOptions OTHER_OPTIONS =
      GcsFileSystemOptions.createFromOptions(
          ImmutableMap.of("gcs.analytics-core.read.thread.count", "4"), "gcs.");

  @AfterEach
  public void tearDown() {
    GcsFileSystemCache.closeAll();
  }

  @Test
  public void acquire_sameScopeAndOptions_sharesFileSystem() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);
    CountingFactory factory = new CountingFactory(fileSystem);

    GcsFileSystem first = GcsFileSystemCache.acquire("scope", OPTIONS, factory);
    GcsFileSystem second = GcsFileSystemCache.acquire("scope", OPTIONS, factory);

    assertThat(first).isSameInstanceAs(fileSystem);
    assertThat(second).isSameInstanceAs(fileSystem);
    assertThat(factory.creations()).isEqualTo(1);
    assertThat(GcsFileSystemCache.size()).isEqualTo(1);
  }

  @Test
  public void acquire_differentScope_doesNotShareFileSystem() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);
    GcsFileSystem otherFileSystem = mock(GcsFileSystem.class);

    GcsFileSystem first = GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystem second =
        GcsFileSystemCache.acquire("other-scope", OPTIONS, () -> otherFileSystem);

    assertThat(first).isSameInstanceAs(fileSystem);
    assertThat(second).isSameInstanceAs(otherFileSystem);
    assertThat(GcsFileSystemCache.size()).isEqualTo(2);
  }

  @Test
  public void acquire_differentOptions_doesNotShareFileSystem() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);
    GcsFileSystem otherFileSystem = mock(GcsFileSystem.class);

    GcsFileSystem first = GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystem second =
        GcsFileSystemCache.acquire("scope", OTHER_OPTIONS, () -> otherFileSystem);

    assertThat(first).isSameInstanceAs(fileSystem);
    assertThat(second).isSameInstanceAs(otherFileSystem);
    assertThat(GcsFileSystemCache.size()).isEqualTo(2);
  }

  @Test
  public void acquire_equalOptionsFromSeparateInstances_sharesFileSystem() {
    GcsFileSystemOptions options =
        GcsFileSystemOptions.createFromOptions(ImmutableMap.of(), "gcs.");
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);
    CountingFactory factory = new CountingFactory(fileSystem);

    GcsFileSystemCache.acquire("scope", OPTIONS, factory);
    GcsFileSystemCache.acquire("scope", options, factory);

    assertThat(factory.creations()).isEqualTo(1);
    assertThat(GcsFileSystemCache.size()).isEqualTo(1);
  }

  @Test
  public void release_withRemainingReferences_keepsFileSystemOpen() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.release(fileSystem);

    verify(fileSystem, never()).close();
    assertThat(GcsFileSystemCache.size()).isEqualTo(1);
  }

  @Test
  public void release_lastReference_closesFileSystem() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.release(fileSystem);
    GcsFileSystemCache.release(fileSystem);

    verify(fileSystem).close();
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  public void release_afterLastReference_isNoOp() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.release(fileSystem);
    GcsFileSystemCache.release(fileSystem);

    verify(fileSystem).close();
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  public void release_uncachedFileSystem_isNoOp() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    assertThat(GcsFileSystemCache.release(fileSystem)).isFalse();
    assertThat(GcsFileSystemCache.release(null)).isFalse();

    verify(fileSystem, never()).close();
    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  public void release_cachedFileSystem_reportsReferenceRemoved() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);

    assertThat(GcsFileSystemCache.release(fileSystem)).isTrue();
    assertThat(GcsFileSystemCache.release(fileSystem)).isTrue();
    assertThat(GcsFileSystemCache.release(fileSystem)).isFalse();
  }

  @Test
  public void acquire_afterRelease_createsNewFileSystem() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);
    GcsFileSystem recreated = mock(GcsFileSystem.class);

    GcsFileSystemCache.acquire("scope", OPTIONS, () -> fileSystem);
    GcsFileSystemCache.release(fileSystem);

    assertThat(GcsFileSystemCache.acquire("scope", OPTIONS, () -> recreated))
        .isSameInstanceAs(recreated);
  }

  @Test
  public void acquire_factoryThrows_cachesNothing() {
    assertThrows(
        IllegalStateException.class,
        () ->
            GcsFileSystemCache.acquire(
                "scope",
                OPTIONS,
                () -> {
                  throw new IllegalStateException("no credentials");
                }));

    assertThat(GcsFileSystemCache.size()).isEqualTo(0);
  }

  @Test
  public void acquire_nullArguments_throws() {
    GcsFileSystem fileSystem = mock(GcsFileSystem.class);

    assertThrows(
        NullPointerException.class,
        () -> GcsFileSystemCache.acquire(null, OPTIONS, () -> fileSystem));
    assertThrows(
        NullPointerException.class,
        () -> GcsFileSystemCache.acquire("scope", null, () -> fileSystem));
    assertThrows(
        NullPointerException.class, () -> GcsFileSystemCache.acquire("scope", OPTIONS, null));
    assertThrows(
        NullPointerException.class, () -> GcsFileSystemCache.acquire("scope", OPTIONS, () -> null));
  }

  private static final class CountingFactory implements Supplier<GcsFileSystem> {
    private final GcsFileSystem fileSystem;
    private final AtomicInteger creations = new AtomicInteger();

    private CountingFactory(GcsFileSystem fileSystem) {
      this.fileSystem = fileSystem;
    }

    @Override
    public GcsFileSystem get() {
      creations.incrementAndGet();
      return fileSystem;
    }

    private int creations() {
      return creations.get();
    }
  }
}
