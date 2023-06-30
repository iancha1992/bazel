// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.NodeBatch;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;

public class FileSystemValueCheckerInferringAncestorsTestBase {
  protected final Scratch scratch = new Scratch();
  protected final List<String> statedPaths = new ArrayList<>();
  protected DefaultSyscallCache syscallCache = DefaultSyscallCache.newBuilder().build();
  protected Root root;
  protected final InMemoryGraph inMemoryGraph = InMemoryGraph.create();

  private Root untrackedRoot;
  Exception throwOnStat;

  @Before
  public void createRoot() throws IOException {
    Path srcRootPath = scratch.dir("/src");
    PathFragment srcRoot = srcRootPath.asFragment();
    FileSystem trackingFileSystem =
        new DelegateFileSystem(scratch.getFileSystem()) {
          @Nullable
          @Override
          public synchronized FileStatus statIfFound(PathFragment path, boolean followSymlinks)
              throws IOException {
            if (throwOnStat != null) {
              Exception toThrow = throwOnStat;
              throwOnStat = null;
              Throwables.propagateIfPossible(toThrow, IOException.class);
              fail("Unexpected exception type");
            }
            statedPaths.add(path.relativeTo(srcRoot).toString());
            return super.statIfFound(path, followSymlinks);
          }
        };
    root = Root.fromPath(trackingFileSystem.getPath(srcRoot));
    scratch.setWorkingDir("/src");
    untrackedRoot = Root.fromPath(srcRootPath);
  }

  @After
  public void checkExceptionThrown() {
    assertThat(throwOnStat).isNull();
    syscallCache.clear();
  }

  protected FileStateKey fileStateValueKey(String relativePath) {
    return FileStateValue.key(
        RootedPath.toRootedPath(root, root.asPath().getRelative(relativePath)));
  }

  protected DirectoryListingStateValue.Key directoryListingStateValueKey(String relativePath) {
    return DirectoryListingStateValue.key(
        RootedPath.toRootedPath(root, root.asPath().getRelative(relativePath)));
  }

  protected FileStateValue fileStateValue(String relativePath) throws IOException {
    return FileStateValue.create(
        RootedPath.toRootedPath(
            untrackedRoot, untrackedRoot.asPath().asFragment().getRelative(relativePath)),
        SyscallCache.NO_CACHE,
        /* tsgm= */ null);
  }

  protected static DirectoryListingStateValue directoryListingStateValue(Dirent... dirents) {
    return DirectoryListingStateValue.create(ImmutableList.copyOf(dirents));
  }

  protected void addDoneNodesAndThenMarkChanged(ImmutableMap<SkyKey, SkyValue> values)
      throws InterruptedException {
    addDoneNodesAndThenMarkChanged(values, /* mtsv= */ null);
  }

  protected void addDoneNodesAndThenMarkChanged(
      ImmutableMap<SkyKey, SkyValue> values, @Nullable Version mtsv) throws InterruptedException {
    for (Entry<SkyKey, SkyValue> entry : values.entrySet()) {
      InMemoryNodeEntry node = addDoneNode(entry.getKey(), entry.getValue(), mtsv);
      node.markDirty(DirtyType.CHANGE);
    }
  }

  protected void addDoneNodes(ImmutableMap<SkyKey, SkyValue> values, @Nullable Version mtsv)
      throws InterruptedException {
    for (Entry<SkyKey, SkyValue> entry : values.entrySet()) {
      addDoneNode(entry.getKey(), entry.getValue(), mtsv);
    }
  }

  @CanIgnoreReturnValue
  private InMemoryNodeEntry addDoneNode(SkyKey key, SkyValue value, @Nullable Version mtsv)
      throws InterruptedException {
    NodeBatch batch = inMemoryGraph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
    InMemoryNodeEntry entry = (InMemoryNodeEntry) batch.get(key);
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();
    entry.setValue(value, Version.minimal(), /* maxTransitiveSourceVersion= */ mtsv);
    return entry;
  }
}
