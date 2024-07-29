// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.RemoteCache.createFailureDetail;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static com.google.devtools.build.lib.remote.util.Utils.getInMemoryOutputPath;
import static com.google.devtools.build.lib.remote.util.Utils.grpcAwareErrorMessage;
import static com.google.devtools.build.lib.remote.util.Utils.shouldUploadLocalResultsToRemoteCache;
import static com.google.devtools.build.lib.remote.util.Utils.waitForBulkTransfer;
import static com.google.devtools.build.lib.util.StringUtil.decodeBytestringUtf8;
import static com.google.devtools.build.lib.util.StringUtil.encodeBytestringUtf8;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.LogFile;
import build.bazel.remote.execution.v2.OutputDirectory;
import build.bazel.remote.execution.v2.OutputFile;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.SymlinkNode;
import build.bazel.remote.execution.v2.Tree;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.buildtool.buildevent.BuildInterruptedEvent;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.SpawnInputExpander.InputWalker;
import com.google.devtools.build.lib.exec.SpawnRunner.SpawnExecutionContext;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.RemoteExecutionService.ActionResultMetadata.DirectoryMetadata;
import com.google.devtools.build.lib.remote.RemoteExecutionService.ActionResultMetadata.FileMetadata;
import com.google.devtools.build.lib.remote.RemoteExecutionService.ActionResultMetadata.SymlinkMetadata;
import com.google.devtools.build.lib.remote.Scrubber.SpawnScrubber;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.common.OperationObserver;
import com.google.devtools.build.lib.remote.common.OutputDigestMismatchException;
import com.google.devtools.build.lib.remote.common.ProgressStatusListener;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext.CachePolicy;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.CachedActionResult;
import com.google.devtools.build.lib.remote.common.RemoteExecutionClient;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.salt.CacheSalt;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.remote.util.Utils.InMemoryOutput;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.worker.WorkerKey;
import com.google.devtools.build.lib.worker.WorkerOptions;
import com.google.devtools.build.lib.worker.WorkerParser;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import io.grpc.Status.Code;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A layer between spawn execution and remote execution exposing primitive operations for remote
 * cache and execution with spawn specific types.
 */
public class RemoteExecutionService {
  private final Reporter reporter;
  private final boolean verboseFailures;
  private final Path execRoot;

  /**
   * Do not use directly, instead use the per-spawn resolver created in {@link
   * #buildRemoteAction(Spawn, SpawnExecutionContext)}.
   */
  private final RemotePathResolver baseRemotePathResolver;

  private final String buildRequestId;
  private final String commandId;
  private final DigestUtil digestUtil;
  private final RemoteOptions remoteOptions;
  @Nullable private final RemoteCache remoteCache;
  @Nullable private final RemoteExecutionClient remoteExecutor;
  private final TempPathGenerator tempPathGenerator;
  @Nullable private final Path captureCorruptedOutputsDir;
  private final Cache<Object, CompletableFuture<MerkleTree>> merkleTreeCache;
  private final Set<String> reportedErrors = new HashSet<>();
  private final Phaser backgroundTaskPhaser = new Phaser(1);

  private final Scheduler scheduler;

  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final AtomicBoolean buildInterrupted = new AtomicBoolean(false);

  @Nullable private final RemoteOutputChecker remoteOutputChecker;
  private final OutputService outputService;

  @Nullable private final Scrubber scrubber;

  public RemoteExecutionService(
      Executor executor,
      Reporter reporter,
      boolean verboseFailures,
      Path execRoot,
      RemotePathResolver remotePathResolver,
      String buildRequestId,
      String commandId,
      DigestUtil digestUtil,
      RemoteOptions remoteOptions,
      @Nullable RemoteCache remoteCache,
      @Nullable RemoteExecutionClient remoteExecutor,
      TempPathGenerator tempPathGenerator,
      @Nullable Path captureCorruptedOutputsDir,
      @Nullable RemoteOutputChecker remoteOutputChecker,
      OutputService outputService) {
    this.reporter = reporter;
    this.verboseFailures = verboseFailures;
    this.execRoot = execRoot;
    this.baseRemotePathResolver = remotePathResolver;
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.digestUtil = digestUtil;
    this.remoteOptions = remoteOptions;
    this.remoteCache = remoteCache;
    this.remoteExecutor = remoteExecutor;

    Caffeine<Object, Object> merkleTreeCacheBuilder = Caffeine.newBuilder().softValues();
    // remoteMerkleTreesCacheSize = 0 means limitless.
    if (remoteOptions.remoteMerkleTreeCacheSize != 0) {
      merkleTreeCacheBuilder.maximumSize(remoteOptions.remoteMerkleTreeCacheSize);
    }
    this.scrubber = remoteOptions.scrubber;
    this.merkleTreeCache = merkleTreeCacheBuilder.build();

    this.tempPathGenerator = tempPathGenerator;
    this.captureCorruptedOutputsDir = captureCorruptedOutputsDir;

    this.scheduler = Schedulers.from(executor, /* interruptibleWorker= */ true);
    this.remoteOutputChecker = remoteOutputChecker;
    this.outputService = outputService;
  }

  private Command buildCommand(
      Collection<? extends ActionInput> outputs,
      List<String> arguments,
      ImmutableMap<String, String> env,
      @Nullable Platform platform,
      RemotePathResolver remotePathResolver,
      @Nullable SpawnScrubber spawnScrubber) {
    Command.Builder command = Command.newBuilder();
    ArrayList<String> outputFiles = new ArrayList<>();
    ArrayList<String> outputDirectories = new ArrayList<>();
    ArrayList<String> outputPaths = new ArrayList<>();
    for (ActionInput output : outputs) {
      String pathString = decodeBytestringUtf8(remotePathResolver.localPathToOutputPath(output));
      if (output.isDirectory()) {
        outputDirectories.add(pathString);
      } else {
        outputFiles.add(pathString);
      }
      outputPaths.add(pathString);
    }
    Collections.sort(outputFiles);
    Collections.sort(outputDirectories);
    Collections.sort(outputPaths);
    command.addAllOutputFiles(outputFiles);
    command.addAllOutputDirectories(outputDirectories);
    command.addAllOutputPaths(outputPaths);

    if (platform != null) {
      command.setPlatform(platform);
    }
    for (String arg : arguments) {
      if (spawnScrubber != null) {
        arg = spawnScrubber.transformArgument(arg);
      }
      command.addArguments(decodeBytestringUtf8(arg));
    }
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(env.keySet());
    for (String var : variables) {
      command
          .addEnvironmentVariablesBuilder()
          .setName(decodeBytestringUtf8(var))
          .setValue(decodeBytestringUtf8(env.get(var)));
    }

    String workingDirectory = remotePathResolver.getWorkingDirectory();
    if (!Strings.isNullOrEmpty(workingDirectory)) {
      command.setWorkingDirectory(decodeBytestringUtf8(workingDirectory));
    }
    return command.build();
  }

  private static boolean useRemoteCache(RemoteOptions options) {
    return !isNullOrEmpty(options.remoteCache) || !isNullOrEmpty(options.remoteExecutor);
  }

  private static boolean useDiskCache(RemoteOptions options) {
    return options.diskCache != null && !options.diskCache.isEmpty();
  }

  public CachePolicy getReadCachePolicy(Spawn spawn) {
    if (remoteCache == null) {
      return CachePolicy.NO_CACHE;
    }

    boolean allowDiskCache = false;
    boolean allowRemoteCache = false;

    if (useRemoteCache(remoteOptions)) {
      allowRemoteCache = remoteOptions.remoteAcceptCached && Spawns.mayBeCachedRemotely(spawn);
      if (useDiskCache(remoteOptions)) {
        // Combined cache. Disk cache is treated as local cache. Actions which are tagged with
        // `no-remote-cache` can still hit the disk cache.
        allowDiskCache = Spawns.mayBeCached(spawn);
      }
    } else {
      // Disk cache only
      allowDiskCache = Spawns.mayBeCached(spawn);
    }

    return CachePolicy.create(allowRemoteCache, allowDiskCache);
  }

  public CachePolicy getWriteCachePolicy(Spawn spawn) {
    if (remoteCache == null) {
      return CachePolicy.NO_CACHE;
    }

    boolean allowDiskCache = false;
    boolean allowRemoteCache = false;

    if (useRemoteCache(remoteOptions)) {
      allowRemoteCache =
          shouldUploadLocalResultsToRemoteCache(remoteOptions, spawn.getExecutionInfo())
              && remoteCache.actionCacheSupportsUpdate();
      if (useDiskCache(remoteOptions)) {
        // Combined cache. Treat the disk cache part as local cache. Actions which are tagged with
        // `no-remote-cache` can still hit the disk cache.
        allowDiskCache = Spawns.mayBeCached(spawn);
      }
    } else {
      // Disk cache only
      allowDiskCache = Spawns.mayBeCached(spawn);
    }

    return CachePolicy.create(allowRemoteCache, allowDiskCache);
  }

  /** Returns {@code true} if the spawn may be executed remotely. */
  public boolean mayBeExecutedRemotely(Spawn spawn) {
    return remoteCache instanceof RemoteExecutionCache
        && remoteExecutor != null
        && Spawns.mayBeExecutedRemotely(spawn)
        && !isScrubbedSpawn(spawn, scrubber);
  }

  @VisibleForTesting
  Cache<Object, CompletableFuture<MerkleTree>> getMerkleTreeCache() {
    return merkleTreeCache;
  }

  private SortedMap<PathFragment, ActionInput> buildOutputDirMap(
      Spawn spawn, RemotePathResolver remotePathResolver) {
    TreeMap<PathFragment, ActionInput> outputDirMap = new TreeMap<>();
    for (ActionInput output : spawn.getOutputFiles()) {
      if (output instanceof Artifact && ((Artifact) output).isTreeArtifact()) {
        outputDirMap.put(
            PathFragment.create(remotePathResolver.getWorkingDirectory())
                .getRelative(remotePathResolver.localPathToOutputPath(output.getExecPath())),
            output);
      }
    }
    return outputDirMap;
  }

  private MerkleTree buildInputMerkleTree(
      Spawn spawn,
      SpawnExecutionContext context,
      ToolSignature toolSignature,
      @Nullable SpawnScrubber spawnScrubber,
      RemotePathResolver remotePathResolver)
      throws IOException, ForbiddenActionInputException {
    // Add output directories to inputs so that they are created as empty directories by the
    // executor. The spec only requires the executor to create the parent directory of an output
    // directory, which differs from the behavior of both local and sandboxed execution.
    SortedMap<PathFragment, ActionInput> outputDirMap =
        buildOutputDirMap(spawn, remotePathResolver);
    boolean useMerkleTreeCache = remoteOptions.remoteMerkleTreeCache;
    if (toolSignature != null || spawnScrubber != null) {
      // The Merkle tree cache is not yet compatible with scrubbing or marking tool files.
      useMerkleTreeCache = false;
    }
    if (useMerkleTreeCache) {
      InputMetadataProvider inputMetadataProvider = context.getInputMetadataProvider();
      ConcurrentLinkedQueue<MerkleTree> subMerkleTrees = new ConcurrentLinkedQueue<>();
      remotePathResolver.walkInputs(
          spawn,
          context,
          (Object nodeKey, InputWalker walker) ->
              subMerkleTrees.add(
                  buildMerkleTreeVisitor(
                      nodeKey,
                      walker,
                      inputMetadataProvider,
                      context.getPathResolver(),
                      spawnScrubber)));
      if (!outputDirMap.isEmpty()) {
        subMerkleTrees.add(
            MerkleTree.build(
                outputDirMap,
                inputMetadataProvider,
                execRoot,
                context.getPathResolver(),
                /* spawnScrubber= */ null,
                digestUtil));
      }
      return MerkleTree.merge(subMerkleTrees, digestUtil);
    } else {
      SortedMap<PathFragment, ActionInput> inputMap =
          remotePathResolver.getInputMapping(
              context, /* willAccessRepeatedly= */ !remoteOptions.remoteDiscardMerkleTrees);
      if (!outputDirMap.isEmpty()) {
        // The map returned by getInputMapping is mutable, but must not be mutated here as it is
        // shared with all other strategies.
        SortedMap<PathFragment, ActionInput> newInputMap = new TreeMap<>();
        newInputMap.putAll(inputMap);
        newInputMap.putAll(outputDirMap);
        inputMap = newInputMap;
      }
      return MerkleTree.build(
          inputMap,
          toolSignature == null ? ImmutableSet.of() : toolSignature.toolInputs,
          context.getInputMetadataProvider(),
          execRoot,
          context.getPathResolver(),
          spawnScrubber,
          digestUtil);
    }
  }

  private MerkleTree buildMerkleTreeVisitor(
      Object nodeKey,
      InputWalker walker,
      InputMetadataProvider inputMetadataProvider,
      ArtifactPathResolver artifactPathResolver,
      @Nullable SpawnScrubber spawnScrubber)
      throws IOException, ForbiddenActionInputException {
    // Deduplicate concurrent computations for the same node. It's not possible to use
    // MerkleTreeCache#get(key, loader) because the loading computation may cause other nodes to be
    // recursively looked up, which is not allowed. Instead, use a future as described at
    // https://github.com/ben-manes/caffeine/wiki/Faq#recursive-computations.
    var freshFuture = new CompletableFuture<MerkleTree>();
    var priorFuture = merkleTreeCache.asMap().putIfAbsent(nodeKey, freshFuture);
    if (priorFuture == null) {
      // No preexisting cache entry, so we must do the computation ourselves.
      try {
        freshFuture.complete(
            uncachedBuildMerkleTreeVisitor(
                walker, inputMetadataProvider, artifactPathResolver, spawnScrubber));
      } catch (Exception e) {
        freshFuture.completeExceptionally(e);
      }
    }
    try {
      return (priorFuture != null ? priorFuture : freshFuture).join();
    } catch (CompletionException e) {
      Throwable cause = checkNotNull(e.getCause());
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof ForbiddenActionInputException) {
        throw (ForbiddenActionInputException) cause;
      } else {
        checkState(cause instanceof RuntimeException);
        throw (RuntimeException) cause;
      }
    }
  }

  @VisibleForTesting
  public MerkleTree uncachedBuildMerkleTreeVisitor(
      InputWalker walker,
      InputMetadataProvider inputMetadataProvider,
      ArtifactPathResolver artifactPathResolver,
      @Nullable SpawnScrubber scrubber)
      throws IOException, ForbiddenActionInputException {
    ConcurrentLinkedQueue<MerkleTree> subMerkleTrees = new ConcurrentLinkedQueue<>();
    subMerkleTrees.add(
        MerkleTree.build(
            walker.getLeavesInputMapping(),
            inputMetadataProvider,
            execRoot,
            artifactPathResolver,
            scrubber,
            digestUtil));
    walker.visitNonLeaves(
        (Object subNodeKey, InputWalker subWalker) ->
            subMerkleTrees.add(
                buildMerkleTreeVisitor(
                    subNodeKey, subWalker, inputMetadataProvider, artifactPathResolver, scrubber)));
    return MerkleTree.merge(subMerkleTrees, digestUtil);
  }

  @Nullable
  private static ByteString buildSalt(Spawn spawn, @Nullable SpawnScrubber spawnScrubber) {
    CacheSalt.Builder saltBuilder =
        CacheSalt.newBuilder().setMayBeExecutedRemotely(Spawns.mayBeExecutedRemotely(spawn));

    String workspace =
        spawn.getExecutionInfo().get(ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE);
    if (workspace != null) {
      saltBuilder.setWorkspace(workspace);
    }

    if (spawnScrubber != null) {
      saltBuilder.setScrubSalt(
          CacheSalt.ScrubSalt.newBuilder().setSalt(spawnScrubber.getSalt()).build());
    }

    return saltBuilder.build().toByteString();
  }

  /**
   * Semaphore for limiting the concurrent number of Merkle tree input roots we compute and keep in
   * memory.
   *
   * <p>When --jobs is set to a high value to let the remote execution service runs many actions in
   * parallel, there is no point in letting the local system compute Merkle trees of input roots
   * with the same amount of parallelism. Not only does this make Bazel feel sluggish and slow to
   * respond to being interrupted, it causes it to exhaust memory.
   *
   * <p>As there is no point in letting Merkle tree input root computation use a higher concurrency
   * than the number of CPUs in the system, use a semaphore to limit the concurrency of
   * buildRemoteAction().
   */
  private final Semaphore remoteActionBuildingSemaphore =
      new Semaphore(Runtime.getRuntime().availableProcessors(), true);

  @Nullable
  private ToolSignature getToolSignature(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ExecException, InterruptedException {
    return remoteOptions.markToolInputs
            && Spawns.supportsWorkers(spawn)
            && !spawn.getToolFiles().isEmpty()
        ? computePersistentWorkerSignature(spawn, context)
        : null;
  }

  private void maybeAcquireRemoteActionBuildingSemaphore(ProfilerTask task)
      throws InterruptedException {
    if (!remoteOptions.throttleRemoteActionBuilding) {
      return;
    }

    try (var c = Profiler.instance().profile(task, "acquiring semaphore")) {
      remoteActionBuildingSemaphore.acquire();
    }
  }

  private void maybeReleaseRemoteActionBuildingSemaphore() {
    if (!remoteOptions.throttleRemoteActionBuilding) {
      return;
    }

    remoteActionBuildingSemaphore.release();
  }

  /** Creates a new {@link RemoteAction} instance from spawn. */
  public RemoteAction buildRemoteAction(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ExecException, ForbiddenActionInputException, InterruptedException {
    maybeAcquireRemoteActionBuildingSemaphore(ProfilerTask.REMOTE_SETUP);
    try {
      // Create a remote path resolver that is aware of the spawn's path mapper, which rewrites
      // the paths of the inputs and outputs as well as paths appearing in the command line for
      // execution. This is necessary to ensure that artifacts are correctly emitted into and staged
      // from the unmapped location locally.
      RemotePathResolver remotePathResolver =
          RemotePathResolver.createMapped(baseRemotePathResolver, execRoot, spawn.getPathMapper());
      ToolSignature toolSignature = getToolSignature(spawn, context);
      SpawnScrubber spawnScrubber = scrubber != null ? scrubber.forSpawn(spawn) : null;
      final MerkleTree merkleTree =
          buildInputMerkleTree(spawn, context, toolSignature, spawnScrubber, remotePathResolver);

      // Get the remote platform properties.
      Platform platform;
      if (toolSignature != null) {
        platform =
            PlatformUtils.getPlatformProto(
                spawn, remoteOptions, ImmutableMap.of("persistentWorkerKey", toolSignature.key));
      } else {
        platform = PlatformUtils.getPlatformProto(spawn, remoteOptions);
      }

      Command command =
          buildCommand(
              spawn.getOutputFiles(),
              spawn.getArguments(),
              spawn.getEnvironment(),
              platform,
              remotePathResolver,
              spawnScrubber);
      Digest commandHash = digestUtil.compute(command);
      Action action =
          Utils.buildAction(
              commandHash,
              merkleTree.getRootDigest(),
              platform,
              context.getTimeout(),
              Spawns.mayBeCachedRemotely(spawn),
              buildSalt(spawn, spawnScrubber));

      ActionKey actionKey = digestUtil.computeActionKey(action);

      RequestMetadata metadata =
          TracingMetadataUtils.buildMetadata(
              buildRequestId, commandId, actionKey.getDigest().getHash(), spawn.getResourceOwner());
      RemoteActionExecutionContext remoteActionExecutionContext =
          RemoteActionExecutionContext.create(
              spawn, context, metadata, getWriteCachePolicy(spawn), getReadCachePolicy(spawn));

      return new RemoteAction(
          spawn,
          context,
          remoteActionExecutionContext,
          remotePathResolver,
          merkleTree,
          commandHash,
          command,
          action,
          actionKey,
          remoteOptions.remoteDiscardMerkleTrees);
    } finally {
      maybeReleaseRemoteActionBuildingSemaphore();
    }
  }

  @Nullable
  private ToolSignature computePersistentWorkerSignature(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ExecException, InterruptedException {
    WorkerParser workerParser =
        new WorkerParser(
            execRoot, Options.getDefaults(WorkerOptions.class), LocalEnvProvider.NOOP, null);
    WorkerKey workerKey = workerParser.compute(spawn, context).getWorkerKey();
    Fingerprint fingerprint = new Fingerprint();
    fingerprint.addBytes(workerKey.getWorkerFilesCombinedHash().asBytes());
    fingerprint.addIterableStrings(workerKey.getArgs());
    fingerprint.addStringMap(workerKey.getEnv());
    return new ToolSignature(
        fingerprint.hexDigestAndReset(), workerKey.getWorkerFilesWithDigests().keySet());
  }

  /** A value class representing the result of remotely executed {@link RemoteAction}. */
  public static class RemoteActionResult {
    private final ActionResult actionResult;
    @Nullable private final ExecuteResponse executeResponse;
    @Nullable private final String cacheName;

    /** Creates a new {@link RemoteActionResult} instance from a cached result. */
    public static RemoteActionResult createFromCache(CachedActionResult cachedActionResult) {
      checkArgument(cachedActionResult != null, "cachedActionResult is null");
      return new RemoteActionResult(
          cachedActionResult.actionResult(), null, cachedActionResult.cacheName());
    }

    /** Creates a new {@link RemoteActionResult} instance from a execute response. */
    public static RemoteActionResult createFromResponse(ExecuteResponse response) {
      checkArgument(response.hasResult(), "response doesn't have result");
      return new RemoteActionResult(response.getResult(), response, /* cacheName */ null);
    }

    public RemoteActionResult(
        ActionResult actionResult,
        @Nullable ExecuteResponse executeResponse,
        @Nullable String cacheName) {
      this.actionResult = actionResult;
      this.executeResponse = executeResponse;
      this.cacheName = cacheName;
    }

    /** Returns the exit code of remote executed action. */
    public int getExitCode() {
      return actionResult.getExitCode();
    }

    public List<OutputFile> getOutputFiles() {
      return actionResult.getOutputFilesList();
    }

    public List<OutputSymlink> getOutputFileSymlinks() {
      return actionResult.getOutputFileSymlinksList();
    }

    public List<OutputDirectory> getOutputDirectories() {
      return actionResult.getOutputDirectoriesList();
    }

    public int getOutputDirectoriesCount() {
      return actionResult.getOutputDirectoriesCount();
    }

    public List<OutputSymlink> getOutputDirectorySymlinks() {
      return actionResult.getOutputDirectorySymlinksList();
    }

    public List<OutputSymlink> getOutputSymlinks() {
      return actionResult.getOutputSymlinksList();
    }

    /**
     * Returns the freeform informational message with details on the execution of the action that
     * may be displayed to the user upon failure or when requested explicitly.
     */
    public String getMessage() {
      return executeResponse != null ? executeResponse.getMessage() : "";
    }

    /** Returns the details of the execution that originally produced this result. */
    public ExecutedActionMetadata getExecutionMetadata() {
      return actionResult.getExecutionMetadata();
    }

    /** Returns whether the action is executed successfully. */
    public boolean success() {
      if (executeResponse != null) {
        if (executeResponse.getStatus().getCode() != Code.OK.value()) {
          return false;
        }
      }

      return actionResult.getExitCode() == 0;
    }

    /** Returns {@code true} if this result is from a cache. */
    public boolean cacheHit() {
      if (executeResponse == null) {
        return true;
      }

      return executeResponse.getCachedResult();
    }

    /** Returns cache name (disk/remote) when {@code cacheHit()} or {@code null} when not */
    @Nullable
    public String cacheName() {
      return cacheName;
    }

    /**
     * Returns the underlying {@link ExecuteResponse} or {@code null} if this result is from a
     * cache.
     */
    @Nullable
    public ExecuteResponse getResponse() {
      return executeResponse;
    }

    @Override
    public boolean equals(Object object) {
      if (!(object instanceof RemoteActionResult)) {
        return false;
      }

      RemoteActionResult that = (RemoteActionResult) object;
      return Objects.equals(actionResult, that.actionResult)
          && Objects.equals(executeResponse, that.executeResponse);
    }

    @Override
    public int hashCode() {
      return Objects.hash(actionResult, executeResponse);
    }
  }

  /** Lookup the remote cache for the given {@link RemoteAction}. {@code null} if not found. */
  @Nullable
  public RemoteActionResult lookupCache(RemoteAction action)
      throws IOException, InterruptedException {
    checkState(
        action.getRemoteActionExecutionContext().getReadCachePolicy().allowAnyCache(),
        "spawn doesn't accept cached result");

    CachedActionResult cachedActionResult =
        remoteCache.downloadActionResult(
            action.getRemoteActionExecutionContext(),
            action.getActionKey(),
            /* inlineOutErr= */ false);

    if (cachedActionResult == null) {
      return null;
    }

    return RemoteActionResult.createFromCache(cachedActionResult);
  }

  private ListenableFuture<FileMetadata> downloadFile(
      RemoteActionExecutionContext context,
      ProgressStatusListener progressStatusListener,
      FileMetadata file,
      Path tmpPath,
      RemotePathResolver remotePathResolver) {
    checkNotNull(remoteCache, "remoteCache can't be null");

    try {
      ListenableFuture<Void> future =
          remoteCache.downloadFile(
              context,
              remotePathResolver.localPathToOutputPath(file.path()),
              tmpPath,
              file.digest(),
              new RemoteCache.DownloadProgressReporter(
                  progressStatusListener,
                  remotePathResolver.localPathToOutputPath(file.path()),
                  file.digest().getSizeBytes()));
      return transform(future, (d) -> file, directExecutor());
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
  }

  private void captureCorruptedOutputs(Exception e) {
    if (captureCorruptedOutputsDir != null) {
      if (e instanceof BulkTransferException) {
        for (Throwable suppressed : e.getSuppressed()) {
          if (suppressed instanceof OutputDigestMismatchException) {
            // Capture corrupted outputs
            try {
              String outputPath = ((OutputDigestMismatchException) suppressed).getOutputPath();
              Path localPath = ((OutputDigestMismatchException) suppressed).getLocalPath();
              Path dst = captureCorruptedOutputsDir.getRelative(outputPath);
              dst.createDirectoryAndParents();

              // Make sure dst is still under captureCorruptedOutputsDir, otherwise
              // IllegalArgumentException will be thrown.
              dst.relativeTo(captureCorruptedOutputsDir);

              FileSystemUtils.copyFile(localPath, dst);
            } catch (Exception ee) {
              e.addSuppressed(ee);
            }
          }
        }
      }
    }
  }

  private void deletePartialDownloadedOutputs(
      Map<Path, Path> realToTmpPath, FileOutErr tmpOutErr, Exception e) throws ExecException {
    try {
      // Delete any (partially) downloaded output files.
      for (Path tmpPath : realToTmpPath.values()) {
        tmpPath.delete();
      }

      tmpOutErr.clearOut();
      tmpOutErr.clearErr();
    } catch (IOException ioEx) {
      ioEx.addSuppressed(e);

      // If deleting of output files failed, we abort the build with a decent error message as
      // any subsequent local execution failure would likely be incomprehensible.
      ExecException execEx =
          new EnvironmentalExecException(
              ioEx,
              createFailureDetail(
                  "Failed to delete output files after incomplete download",
                  RemoteExecution.Code.INCOMPLETE_OUTPUT_DOWNLOAD_CLEANUP_FAILURE));
      execEx.addSuppressed(e);
      throw execEx;
    }
  }

  /** Moves the locally created outputs from their temporary location to their declared location. */
  private void moveOutputsToFinalLocation(
      Iterable<Path> localOutputs, Map<Path, Path> realToTmpPath) throws IOException {
    // Move the output files from their temporary name to the actual output file name. Executable
    // bit is ignored since the file permission will be changed to 0555 after execution.
    for (Path realPath : localOutputs) {
      Path tmpPath = Preconditions.checkNotNull(realToTmpPath.get(realPath));
      realPath.getParentDirectory().createDirectoryAndParents();
      FileSystemUtils.moveFile(tmpPath, realPath);
    }
  }

  private void createSymlinks(Iterable<SymlinkMetadata> symlinks) throws IOException {
    for (SymlinkMetadata symlink : symlinks) {
      Preconditions.checkNotNull(
              symlink.path().getParentDirectory(),
              "Failed creating directory and parents for %s",
              symlink.path())
          .createDirectoryAndParents();
      // If a directory output is being materialized as a symlink, we must first delete the
      // preexisting empty directory.
      if (symlink.path().exists(Symlinks.NOFOLLOW)
          && symlink.path().isDirectory(Symlinks.NOFOLLOW)) {
        symlink.path().delete();
      }
      symlink.path().createSymbolicLink(symlink.target());
    }
  }

  /** In-memory representation of action result metadata. */
  static class ActionResultMetadata {

    static class SymlinkMetadata {
      private final Path path;
      private final PathFragment target;

      private SymlinkMetadata(Path path, PathFragment target) {
        this.path = path;
        this.target = target;
      }

      public Path path() {
        return path;
      }

      public PathFragment target() {
        return target;
      }
    }

    public static class FileMetadata {
      private final Path path;
      private final Digest digest;
      private final boolean isExecutable;

      private FileMetadata(Path path, Digest digest, boolean isExecutable) {
        this.path = path;
        this.digest = digest;
        this.isExecutable = isExecutable;
      }

      public Path path() {
        return path;
      }

      public Digest digest() {
        return digest;
      }

      public boolean isExecutable() {
        return isExecutable;
      }
    }

    static class DirectoryMetadata {
      private final ImmutableList<FileMetadata> files;
      private final ImmutableList<SymlinkMetadata> symlinks;

      private DirectoryMetadata(
          ImmutableList<FileMetadata> files, ImmutableList<SymlinkMetadata> symlinks) {
        this.files = files;
        this.symlinks = symlinks;
      }

      public ImmutableList<FileMetadata> files() {
        return files;
      }

      public ImmutableList<SymlinkMetadata> symlinks() {
        return symlinks;
      }
    }

    private final ImmutableMap<Path, FileMetadata> files;
    private final ImmutableMap<Path, SymlinkMetadata> symlinks;
    private final ImmutableMap<Path, DirectoryMetadata> directories;

    private ActionResultMetadata(
        ImmutableMap<Path, FileMetadata> files,
        ImmutableMap<Path, SymlinkMetadata> symlinks,
        ImmutableMap<Path, DirectoryMetadata> directories) {
      this.files = files;
      this.symlinks = symlinks;
      this.directories = directories;
    }

    @Nullable
    public FileMetadata file(Path path) {
      return files.get(path);
    }

    @Nullable
    public DirectoryMetadata directory(Path path) {
      return directories.get(path);
    }

    public Collection<FileMetadata> files() {
      return files.values();
    }

    public ImmutableSet<Entry<Path, DirectoryMetadata>> directories() {
      return directories.entrySet();
    }

    public Collection<SymlinkMetadata> symlinks() {
      return symlinks.values();
    }
  }

  private DirectoryMetadata parseDirectory(
      Path parent, Directory dir, Map<Digest, Directory> childDirectoriesMap) {
    ImmutableList.Builder<FileMetadata> filesBuilder = ImmutableList.builder();
    for (FileNode file : dir.getFilesList()) {
      filesBuilder.add(
          new FileMetadata(
              parent.getRelative(file.getName()), file.getDigest(), file.getIsExecutable()));
    }

    ImmutableList.Builder<SymlinkMetadata> symlinksBuilder = ImmutableList.builder();
    for (SymlinkNode symlink : dir.getSymlinksList()) {
      symlinksBuilder.add(
          new SymlinkMetadata(
              parent.getRelative(symlink.getName()), PathFragment.create(symlink.getTarget())));
    }

    for (DirectoryNode directoryNode : dir.getDirectoriesList()) {
      Path childPath = parent.getRelative(directoryNode.getName());
      Directory childDir =
          Preconditions.checkNotNull(childDirectoriesMap.get(directoryNode.getDigest()));
      DirectoryMetadata childMetadata = parseDirectory(childPath, childDir, childDirectoriesMap);
      filesBuilder.addAll(childMetadata.files());
      symlinksBuilder.addAll(childMetadata.symlinks());
    }

    return new DirectoryMetadata(filesBuilder.build(), symlinksBuilder.build());
  }

  ActionResultMetadata parseActionResultMetadata(
      RemoteActionExecutionContext context,
      RemoteActionResult result,
      RemotePathResolver remotePathResolver)
      throws IOException, InterruptedException {
    checkNotNull(remoteCache, "remoteCache can't be null");

    Map<Path, ListenableFuture<Tree>> dirMetadataDownloads =
        Maps.newHashMapWithExpectedSize(result.getOutputDirectoriesCount());
    for (OutputDirectory dir : result.getOutputDirectories()) {
      var outputPath = encodeBytestringUtf8(dir.getPath());
      dirMetadataDownloads.put(
          remotePathResolver.outputPathToLocalPath(outputPath),
          Futures.transformAsync(
              remoteCache.downloadBlob(context, outputPath, dir.getTreeDigest()),
              (treeBytes) ->
                  immediateFuture(Tree.parseFrom(treeBytes, ExtensionRegistry.getEmptyRegistry())),
              directExecutor()));
    }

    waitForBulkTransfer(dirMetadataDownloads.values(), /* cancelRemainingOnInterrupt= */ true);

    ImmutableMap.Builder<Path, DirectoryMetadata> directories = ImmutableMap.builder();
    for (Map.Entry<Path, ListenableFuture<Tree>> metadataDownload :
        dirMetadataDownloads.entrySet()) {
      Path path = metadataDownload.getKey();
      Tree directoryTree = getFromFuture(metadataDownload.getValue());
      Map<Digest, Directory> childrenMap = new HashMap<>();
      for (Directory childDir : directoryTree.getChildrenList()) {
        childrenMap.put(digestUtil.compute(childDir), childDir);
      }

      directories.put(path, parseDirectory(path, directoryTree.getRoot(), childrenMap));
    }

    ImmutableMap.Builder<Path, FileMetadata> files = ImmutableMap.builder();
    for (OutputFile outputFile : result.getOutputFiles()) {
      Path localPath =
          remotePathResolver.outputPathToLocalPath(encodeBytestringUtf8(outputFile.getPath()));
      files.put(
          localPath,
          new FileMetadata(localPath, outputFile.getDigest(), outputFile.getIsExecutable()));
    }

    var symlinkMap = new HashMap<Path, SymlinkMetadata>();
    var outputSymlinks =
        Iterables.concat(
            result.getOutputFileSymlinks(),
            result.getOutputDirectorySymlinks(),
            result.getOutputSymlinks());
    for (var symlink : outputSymlinks) {
      var localPath =
          remotePathResolver.outputPathToLocalPath(encodeBytestringUtf8(symlink.getPath()));
      var target = PathFragment.create(symlink.getTarget());
      var existingMetadata = symlinkMap.get(localPath);
      if (existingMetadata != null) {
        if (!target.equals(existingMetadata.target())) {
          throw new IOException(
              String.format(
                  "Symlink path collision: '%s' is mapped to both '%s' and '%s'. Action Result"
                      + " should not contain multiple targets for the same symlink.",
                  localPath, existingMetadata.target(), target));
        }
        continue;
      }

      symlinkMap.put(localPath, new SymlinkMetadata(localPath, target));
    }

    return new ActionResultMetadata(
        files.buildOrThrow(), ImmutableMap.copyOf(symlinkMap), directories.buildOrThrow());
  }

  /**
   * Downloads the outputs of a remotely executed action and injects their metadata.
   *
   * <p>For a successful action, the {@link RemoteOutputChecker} is consulted to determine which of
   * the outputs should be downloaded. For a failed action, all outputs are downloaded. The action
   * stdout and stderr, as well as the in-memory output when present, are always downloaded even in
   * the success case. Any outputs that are not downloaded have their metadata injected into the
   * {@link RemoteActionFileSystem}.
   *
   * <p>In case of download failure, all of the already downloaded outputs are deleted.
   *
   * @return The in-memory output if the spawn had one, otherwise null.
   */
  @Nullable
  public InMemoryOutput downloadOutputs(RemoteAction action, RemoteActionResult result)
      throws InterruptedException, IOException, ExecException {
    checkState(!shutdown.get(), "shutdown");
    checkNotNull(remoteCache, "remoteCache can't be null");

    RemoteActionFileSystem remoteActionFileSystem = null;
    boolean hasBazelOutputService = outputService instanceof BazelOutputService;
    if (!hasBazelOutputService) {
      FileSystem actionFileSystem = action.getSpawnExecutionContext().getActionFileSystem();
      checkState(
          actionFileSystem instanceof RemoteActionFileSystem,
          "expected the ActionFileSystem to be a RemoteActionFileSystem");
      remoteActionFileSystem = (RemoteActionFileSystem) actionFileSystem;
    }

    ProgressStatusListener progressStatusListener = action.getSpawnExecutionContext()::report;
    RemoteActionExecutionContext context = action.getRemoteActionExecutionContext();
    if (result.executeResponse != null) {
      // Always read from remote cache for just remotely executed action.
      context = context.withReadCachePolicy(context.getReadCachePolicy().addRemoteCache());
    }

    ActionResultMetadata metadata;
    try (SilentCloseable c = Profiler.instance().profile("Remote.parseActionResultMetadata")) {
      metadata = parseActionResultMetadata(context, result, action.getRemotePathResolver());
    }

    // The expiration time for remote cache entries.
    var expireAtEpochMilli = Instant.now().plus(remoteOptions.remoteCacheTtl).toEpochMilli();

    ActionInput inMemoryOutput = null;
    AtomicReference<byte[]> inMemoryOutputData = new AtomicReference<>(null);
    PathFragment inMemoryOutputPath = getInMemoryOutputPath(action.getSpawn());
    if (inMemoryOutputPath != null) {
      for (ActionInput output : action.getSpawn().getOutputFiles()) {
        if (output.getExecPath().equals(inMemoryOutputPath)) {
          inMemoryOutput = output;
          break;
        }
      }
    }

    // Collect the set of files to download.
    ImmutableList.Builder<ListenableFuture<FileMetadata>> downloadsBuilder =
        ImmutableList.builder();

    // Download into temporary paths, then move everything at the end.
    // This avoids holding the output lock while downloading, which would prevent the local branch
    // from completing sooner under the dynamic execution strategy.
    Map<Path, Path> realToTmpPath = new HashMap<>();

    for (FileMetadata file : metadata.files()) {
      if (realToTmpPath.containsKey(file.path)) {
        continue;
      }

      var execPath = file.path.relativeTo(execRoot);
      var isInMemoryOutputFile = inMemoryOutput != null && execPath.equals(inMemoryOutputPath);
      if (!isInMemoryOutputFile && shouldDownload(result, execPath)) {
        Path tmpPath = tempPathGenerator.generateTempPath();
        realToTmpPath.put(file.path, tmpPath);
        downloadsBuilder.add(
            downloadFile(
                context, progressStatusListener, file, tmpPath, action.getRemotePathResolver()));
      } else {
        if (hasBazelOutputService) {
          downloadsBuilder.add(immediateFuture(file));
        } else {
          checkNotNull(remoteActionFileSystem)
              .injectRemoteFile(
                  file.path().asFragment(),
                  DigestUtil.toBinaryDigest(file.digest()),
                  file.digest().getSizeBytes(),
                  expireAtEpochMilli);
        }

        if (isInMemoryOutputFile) {
          downloadsBuilder.add(
              transform(
                  remoteCache.downloadBlob(
                      context, inMemoryOutputPath.getPathString(), file.digest()),
                  data -> {
                    inMemoryOutputData.set(data);
                    return null;
                  },
                  directExecutor()));
        }
      }
    }

    for (Map.Entry<Path, DirectoryMetadata> entry : metadata.directories()) {
      for (FileMetadata file : entry.getValue().files()) {
        if (realToTmpPath.containsKey(file.path)) {
          continue;
        }
        if (shouldDownload(result, file.path.relativeTo(execRoot))) {
          Path tmpPath = tempPathGenerator.generateTempPath();
          realToTmpPath.put(file.path, tmpPath);
          downloadsBuilder.add(
              downloadFile(
                  context, progressStatusListener, file, tmpPath, action.getRemotePathResolver()));
        } else {
          if (hasBazelOutputService) {
            downloadsBuilder.add(immediateFuture(file));
          } else {
            checkNotNull(remoteActionFileSystem)
                .injectRemoteFile(
                    file.path().asFragment(),
                    DigestUtil.toBinaryDigest(file.digest()),
                    file.digest().getSizeBytes(),
                    expireAtEpochMilli);
          }
        }
      }
    }

    FileOutErr outErr = action.getSpawnExecutionContext().getFileOutErr();

    // Always download the action stdout/stderr.
    FileOutErr tmpOutErr = outErr.childOutErr();
    List<ListenableFuture<Void>> outErrDownloads =
        remoteCache.downloadOutErr(context, result.actionResult, tmpOutErr);
    for (ListenableFuture<Void> future : outErrDownloads) {
      downloadsBuilder.add(transform(future, (v) -> null, directExecutor()));
    }

    ImmutableList<ListenableFuture<FileMetadata>> downloads = downloadsBuilder.build();
    try (SilentCloseable c = Profiler.instance().profile("Remote.download")) {
      waitForBulkTransfer(downloads, /* cancelRemainingOnInterrupt= */ true);
    } catch (Exception e) {
      // TODO(bazel-team): Consider adding better case-by-case exception handling instead of just
      // rethrowing
      captureCorruptedOutputs(e);
      deletePartialDownloadedOutputs(realToTmpPath, tmpOutErr, e);
      throw e;
    }

    FileOutErr.dump(tmpOutErr, outErr);

    // Ensure that we are the only ones writing to the output files when using the dynamic spawn
    // strategy.
    action
        .getSpawnExecutionContext()
        .lockOutputFiles(result.getExitCode(), result.getMessage(), tmpOutErr);
    // Will these be properly garbage-collected if the above throws an exception?
    tmpOutErr.clearOut();
    tmpOutErr.clearErr();

    List<FileMetadata> finishedDownloads = new ArrayList<>(downloads.size());
    for (ListenableFuture<FileMetadata> finishedDownload : downloads) {
      FileMetadata outputFile = getFromFuture(finishedDownload);
      if (outputFile != null) {
        finishedDownloads.add(outputFile);
      }
    }

    if (hasBazelOutputService) {
      // TODO(chiwang): Stage directories directly
      ((BazelOutputService) outputService).stageArtifacts(finishedDownloads);
    } else {
      moveOutputsToFinalLocation(
          Iterables.transform(finishedDownloads, FileMetadata::path), realToTmpPath);
    }

    List<SymlinkMetadata> symlinksInDirectories = new ArrayList<>();
    for (Entry<Path, DirectoryMetadata> entry : metadata.directories()) {
      for (SymlinkMetadata symlink : entry.getValue().symlinks()) {
        symlinksInDirectories.add(symlink);
      }
    }

    Iterable<SymlinkMetadata> symlinks =
        Iterables.concat(metadata.symlinks(), symlinksInDirectories);

    // Create the symbolic links after all downloads are finished, because dangling symlinks
    // might not be supported on all platforms.
    createSymlinks(symlinks);

    if (result.success()) {
      // Check that all mandatory outputs are created.
      for (ActionInput output : action.getSpawn().getOutputFiles()) {
        if (action.getSpawn().isMandatoryOutput(output)) {
          // In the past, remote execution did not create output directories if the action didn't do
          // this explicitly. This check only remains so that old remote cache entries that do not
          // include empty output directories remain valid.
          if (output instanceof Artifact && ((Artifact) output).isTreeArtifact()) {
            continue;
          }

          Path localPath = execRoot.getRelative(output.getExecPath());
          if (!metadata.files.containsKey(localPath)
              && !metadata.directories.containsKey(localPath)
              && !metadata.symlinks.containsKey(localPath)) {
            throw new IOException(
                String.format("mandatory output %s was not created", prettyPrint(output)));
          }
        }
      }

      // When downloading outputs from just remotely executed action, the action result comes from
      // Execution response which means, if disk cache is enabled, action result hasn't been
      // uploaded to it. Upload action result to disk cache here so next build could hit it.
      if (useDiskCache(remoteOptions) && result.executeResponse != null) {
        getFromFuture(
            remoteCache.uploadActionResult(
                context.withWriteCachePolicy(CachePolicy.DISK_CACHE_ONLY),
                action.getActionKey(),
                result.actionResult));
      }
    }

    if (inMemoryOutput != null && inMemoryOutputData.get() != null) {
      return new InMemoryOutput(inMemoryOutput, ByteString.copyFrom(inMemoryOutputData.get()));
    }

    return null;
  }

  /** An ongoing local execution of a spawn. */
  public static final class LocalExecution {
    private final RemoteAction action;
    private final SettableFuture<SpawnResult> spawnResultFuture;

    private LocalExecution(RemoteAction action) {
      this.action = action;
      this.spawnResultFuture = SettableFuture.create();
    }

    /**
     * Creates a new {@link LocalExecution} instance tracking the potential local execution of the
     * given {@link RemoteAction} if there is a chance that the same action will be executed by a
     * different Spawn.
     *
     * <p>This is only done for local (as in, non-remote) execution as remote executors are expected
     * to already have deduplication mechanisms for actions in place, perhaps even across different
     * builds and clients.
     */
    @Nullable
    public static LocalExecution createIfDeduplicatable(RemoteAction action) {
      if (action.getSpawn().getPathMapper().isNoop()) {
        return null;
      }
      return new LocalExecution(action);
    }

    /**
     * Signals to all potential consumers of the {@link #spawnResultFuture} that this execution has
     * been cancelled and that the result will not be available.
     */
    public void cancel() {
      spawnResultFuture.cancel(true);
    }
  }

  /**
   * Makes the {@link SpawnResult} available to all parallel {@link Spawn}s for the same {@link
   * RemoteAction} waiting for it or notifies them that the spawn failed.
   *
   * @return Whether the spawn result should be uploaded to the cache.
   */
  public boolean commitResultAndDecideWhetherToUpload(
      SpawnResult result, @Nullable LocalExecution execution) {
    if (result.status().equals(SpawnResult.Status.SUCCESS) && result.exitCode() == 0) {
      if (execution != null) {
        execution.spawnResultFuture.set(result);
      }
      return true;
    } else {
      if (execution != null) {
        execution.spawnResultFuture.cancel(true);
      }
      return false;
    }
  }

  /**
   * Reuses the outputs of a concurrent local execution of the same RemoteAction in a different
   * spawn.
   *
   * <p>Since each output file is generated by a unique action and actions generally take care to
   * run a unique spawn for each output file, this method is only useful with path mapping enabled,
   * which allows different spawns in a single build to have the same RemoteAction.ActionKey.
   *
   * @return The {@link SpawnResult} of the previous execution if it was successful, otherwise null.
   */
  @Nullable
  public SpawnResult waitForAndReuseOutputs(RemoteAction action, LocalExecution previousExecution)
      throws InterruptedException, IOException {
    checkState(!shutdown.get(), "shutdown");

    SpawnResult previousSpawnResult;
    try {
      previousSpawnResult = previousExecution.spawnResultFuture.get();
    } catch (CancellationException | ExecutionException e) {
      if (e.getCause() != null) {
        Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
        Throwables.throwIfUnchecked(e.getCause());
      }
      // The spawn this action was deduplicated against failed due to an exception or
      // non-zero exit code. Since it isn't possible to transparently replay its failure for the
      // current spawn, we rerun the action instead.
      return null;
    }

    Preconditions.checkArgument(
        action.getActionKey().equals(previousExecution.action.getActionKey()));

    ImmutableMap<Path, ActionInput> previousOutputs =
        previousExecution.action.getSpawn().getOutputFiles().stream()
            .collect(toImmutableMap(output -> execRoot.getRelative(output.getExecPath()), o -> o));
    Map<Path, Path> realToTmpPath = new HashMap<>();
    for (String output : action.getCommand().getOutputPathsList()) {
      Path sourcePath =
          previousExecution
              .action
              .getRemotePathResolver()
              .outputPathToLocalPath(encodeBytestringUtf8(output));
      ActionInput outputArtifact = previousOutputs.get(sourcePath);
      Path tmpPath = tempPathGenerator.generateTempPath();
      tmpPath.getParentDirectory().createDirectoryAndParents();
      try {
        if (outputArtifact.isDirectory()) {
          tmpPath.createDirectory();
          FileSystemUtils.copyTreesBelow(sourcePath, tmpPath, Symlinks.NOFOLLOW);
        } else if (outputArtifact.isSymlink()) {
          FileSystemUtils.ensureSymbolicLink(tmpPath, sourcePath.readSymbolicLink());
        } else {
          FileSystemUtils.copyFile(sourcePath, tmpPath);
        }
      } catch (FileNotFoundException e) {
        // The spawn this action was deduplicated against failed to create an output file. If the
        // output is mandatory, we cannot reuse the previous execution.
        if (action.getSpawn().isMandatoryOutput(outputArtifact)) {
          return null;
        }
      }

      Path targetPath =
          action.getRemotePathResolver().outputPathToLocalPath(encodeBytestringUtf8(output));
      realToTmpPath.put(targetPath, tmpPath);
    }

    // TODO: FileOutErr is action-scoped, not spawn-scoped, but this is not a problem for the
    //  current use case of supporting deduplication of path mapped spawns:
    //  1. Starlark and C++ compilation actions always create a single spawn.
    //  2. Java compilation actions may run a fallback spawn, but reset the FileOutErr before
    //     running it.
    //  If this changes, we will need to introduce a spawn-scoped OutErr.
    FileOutErr.dump(
        previousExecution.action.getSpawnExecutionContext().getFileOutErr(),
        action.getSpawnExecutionContext().getFileOutErr());

    action
        .getSpawnExecutionContext()
        .lockOutputFiles(
            previousSpawnResult.exitCode(),
            previousSpawnResult.getFailureMessage(),
            action.getSpawnExecutionContext().getFileOutErr());
    // All outputs are created locally.
    moveOutputsToFinalLocation(realToTmpPath.keySet(), realToTmpPath);

    return previousSpawnResult;
  }

  private boolean shouldDownload(RemoteActionResult result, PathFragment execPath) {
    if (outputService instanceof BazelOutputService) {
      return false;
    }

    // In case the action failed, download all outputs. It might be helpful for debugging and there
    // is no point in injecting output metadata of a failed action.
    if (result.getExitCode() != 0) {
      return true;
    }
    return remoteOutputChecker.shouldDownloadOutput(execPath);
  }

  private static String prettyPrint(ActionInput actionInput) {
    if (actionInput instanceof Artifact) {
      return ((Artifact) actionInput).prettyPrint();
    } else {
      return actionInput.getExecPathString();
    }
  }

  private Single<UploadManifest> buildUploadManifestAsync(
      RemoteAction action, SpawnResult spawnResult) {
    return Single.fromCallable(
        () -> {
          try (SilentCloseable c = Profiler.instance().profile("build upload manifest")) {
            ImmutableList.Builder<Path> outputFiles = ImmutableList.builder();
            // Check that all mandatory outputs are created.
            for (ActionInput outputFile : action.getSpawn().getOutputFiles()) {
              Symlinks followSymlinks =
                  outputFile.isSymlink() ? Symlinks.NOFOLLOW : Symlinks.FOLLOW;
              Path localPath = execRoot.getRelative(outputFile.getExecPath());
              if (action.getSpawn().isMandatoryOutput(outputFile)
                  && !localPath.exists(followSymlinks)) {
                throw new IOException(
                    "Expected output " + prettyPrint(outputFile) + " was not created locally.");
              }
              outputFiles.add(localPath);
            }

            return UploadManifest.create(
                remoteOptions,
                remoteCache.getCacheCapabilities(),
                digestUtil,
                action.getRemotePathResolver(),
                action.getActionKey(),
                action.getAction(),
                action.getCommand(),
                outputFiles.build(),
                action.getSpawnExecutionContext().getFileOutErr(),
                spawnResult.exitCode(),
                spawnResult.getStartTime(),
                spawnResult.getWallTimeInMs());
          }
        });
  }

  @VisibleForTesting
  UploadManifest buildUploadManifest(RemoteAction action, SpawnResult spawnResult)
      throws IOException, ExecException, InterruptedException {
    try {
      return buildUploadManifestAsync(action, spawnResult).blockingGet();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        Throwables.throwIfInstanceOf(cause, IOException.class);
        Throwables.throwIfInstanceOf(cause, ExecException.class);
        Throwables.throwIfInstanceOf(cause, InterruptedException.class);
      }
      throw e;
    }
  }

  /** Upload outputs of a remote action which was executed locally to remote cache. */
  public void uploadOutputs(RemoteAction action, SpawnResult spawnResult, Runnable onUploadComplete)
      throws InterruptedException, ExecException {
    checkState(!shutdown.get(), "shutdown");
    checkState(
        action.getRemoteActionExecutionContext().getWriteCachePolicy().allowAnyCache(),
        "spawn shouldn't upload local result");
    checkState(
        SpawnResult.Status.SUCCESS.equals(spawnResult.status()) && spawnResult.exitCode() == 0,
        "shouldn't upload outputs of failed local action");

    if (remoteOptions.remoteCacheAsync) {
      Single.using(
              remoteCache::retain,
              remoteCache ->
                  buildUploadManifestAsync(action, spawnResult)
                      .flatMap(
                          manifest ->
                              manifest.uploadAsync(
                                  action.getRemoteActionExecutionContext(), remoteCache, reporter)),
              RemoteCache::release)
          .subscribeOn(scheduler)
          .subscribe(
              new SingleObserver<ActionResult>() {
                long startTime = 0;

                @Override
                public void onSubscribe(@NonNull Disposable d) {
                  backgroundTaskPhaser.register();
                  startTime = Profiler.nanoTimeMaybe();
                }

                @Override
                public void onSuccess(@NonNull ActionResult actionResult) {
                  Profiler.instance()
                      .completeTask(startTime, ProfilerTask.UPLOAD_TIME, "upload outputs");
                  backgroundTaskPhaser.arriveAndDeregister();
                  onUploadComplete.run();
                }

                @Override
                public void onError(@NonNull Throwable e) {
                  Profiler.instance()
                      .completeTask(startTime, ProfilerTask.UPLOAD_TIME, "upload outputs");
                  backgroundTaskPhaser.arriveAndDeregister();
                  reportUploadError(e);
                  onUploadComplete.run();
                }
              });
    } else {
      try (SilentCloseable c =
          Profiler.instance().profile(ProfilerTask.UPLOAD_TIME, "upload outputs")) {
        UploadManifest manifest = buildUploadManifest(action, spawnResult);
        manifest.upload(action.getRemoteActionExecutionContext(), remoteCache, reporter);
      } catch (IOException e) {
        reportUploadError(e);
      } finally {
        onUploadComplete.run();
      }
    }
  }

  private void reportUploadError(Throwable error) {
    if (buildInterrupted.get()) {
      // If build interrupted, ignores all the errors
      return;
    }

    String errorMessage = "Remote Cache: " + grpcAwareErrorMessage(error, verboseFailures);

    report(Event.warn(errorMessage));
  }

  /**
   * Upload inputs of a remote action to remote cache if they are not presented already.
   *
   * <p>Must be called before calling {@link #executeRemotely}.
   */
  public void uploadInputsIfNotPresent(RemoteAction action, boolean force)
      throws IOException, ExecException, ForbiddenActionInputException, InterruptedException {
    checkState(!shutdown.get(), "shutdown");
    checkState(mayBeExecutedRemotely(action.getSpawn()), "spawn can't be executed remotely");

    RemoteExecutionCache remoteExecutionCache = (RemoteExecutionCache) remoteCache;
    // Upload the command and all the inputs into the remote cache.
    Map<Digest, Message> additionalInputs = Maps.newHashMapWithExpectedSize(2);
    additionalInputs.put(action.getActionKey().getDigest(), action.getAction());
    additionalInputs.put(action.getCommandHash(), action.getCommand());

    // As uploading depends on having the full input root in memory, limit
    // concurrency. This prevents memory exhaustion. We assume that
    // ensureInputsPresent() provides enough parallelism to saturate the
    // network connection.
    maybeAcquireRemoteActionBuildingSemaphore(ProfilerTask.UPLOAD_TIME);
    try {
      MerkleTree merkleTree = action.getMerkleTree();
      if (merkleTree == null) {
        // --experimental_remote_discard_merkle_trees was provided.
        // Recompute the input root.
        Spawn spawn = action.getSpawn();
        SpawnExecutionContext context = action.getSpawnExecutionContext();
        ToolSignature toolSignature = getToolSignature(spawn, context);
        SpawnScrubber spawnScrubber = scrubber != null ? scrubber.forSpawn(spawn) : null;
        merkleTree =
            buildInputMerkleTree(
                spawn, context, toolSignature, spawnScrubber, action.getRemotePathResolver());
      }

      remoteExecutionCache.ensureInputsPresent(
          action
              .getRemoteActionExecutionContext()
              .withWriteCachePolicy(CachePolicy.REMOTE_CACHE_ONLY), // Only upload to remote cache
          merkleTree,
          additionalInputs,
          force,
          reporter);
    } finally {
      maybeReleaseRemoteActionBuildingSemaphore();
    }
  }

  /**
   * Executes the remote action remotely and returns the result.
   *
   * @param acceptCachedResult tells remote execution server whether it should used cached result.
   * @param observer receives status updates during the execution.
   */
  public RemoteActionResult executeRemotely(
      RemoteAction action, boolean acceptCachedResult, OperationObserver observer)
      throws IOException, InterruptedException {
    checkState(!shutdown.get(), "shutdown");
    checkState(mayBeExecutedRemotely(action.getSpawn()), "spawn can't be executed remotely");

    ExecuteRequest.Builder requestBuilder =
        ExecuteRequest.newBuilder()
            .setInstanceName(remoteOptions.remoteInstanceName)
            .setDigestFunction(digestUtil.getDigestFunction())
            .setActionDigest(action.getActionKey().getDigest())
            .setSkipCacheLookup(!acceptCachedResult);
    if (remoteOptions.remoteResultCachePriority != 0) {
      requestBuilder
          .getResultsCachePolicyBuilder()
          .setPriority(remoteOptions.remoteResultCachePriority);
    }
    if (remoteOptions.remoteExecutionPriority != 0) {
      requestBuilder.getExecutionPolicyBuilder().setPriority(remoteOptions.remoteExecutionPriority);
    }

    ExecuteRequest request = requestBuilder.build();

    ExecuteResponse reply =
        remoteExecutor.executeRemotely(action.getRemoteActionExecutionContext(), request, observer);

    return RemoteActionResult.createFromResponse(reply);
  }

  /** A value classes representing downloaded server logs. */
  public static class ServerLogs {
    public int logCount;
    public Path directory;
    @Nullable public Path lastLogPath;
  }

  /** Downloads server logs from a remotely executed action if any. */
  public ServerLogs maybeDownloadServerLogs(RemoteAction action, ExecuteResponse resp, Path logDir)
      throws InterruptedException, IOException {
    checkState(!shutdown.get(), "shutdown");
    checkNotNull(remoteCache, "remoteCache can't be null");
    ServerLogs serverLogs = new ServerLogs();
    serverLogs.directory = logDir.getRelative(action.getActionId());

    ActionResult actionResult = resp.getResult();
    if (resp.getServerLogsCount() > 0
        && (actionResult.getExitCode() != 0 || resp.getStatus().getCode() != Code.OK.value())) {
      for (Map.Entry<String, LogFile> e : resp.getServerLogsMap().entrySet()) {
        if (e.getValue().getHumanReadable()) {
          serverLogs.lastLogPath = serverLogs.directory.getRelative(e.getKey());
          serverLogs.logCount++;
          getFromFuture(
              remoteCache.downloadFile(
                  action.getRemoteActionExecutionContext(),
                  serverLogs.lastLogPath,
                  e.getValue().getDigest()));
        }
      }
    }

    return serverLogs;
  }

  @Subscribe
  public void onBuildInterrupted(BuildInterruptedEvent event) {
    buildInterrupted.set(true);
  }

  /**
   * Shuts the service down. Wait for active network I/O to finish but new requests are rejected.
   */
  public void shutdown() {
    if (!shutdown.compareAndSet(false, true)) {
      return;
    }

    if (buildInterrupted.get()) {
      Thread.currentThread().interrupt();
    }

    if (remoteCache != null) {
      try {
        backgroundTaskPhaser.awaitAdvanceInterruptibly(backgroundTaskPhaser.arrive());
      } catch (InterruptedException e) {
        buildInterrupted.set(true);
        remoteCache.shutdownNow();
        Thread.currentThread().interrupt();
      }

      // Only release the remoteCache once all background tasks have been finished. Otherwise, the
      // last task might try to close the remoteCache inside the callback of network response which
      // might cause deadlocks.
      // See https://github.com/bazelbuild/bazel/issues/21568.
      remoteCache.release();
    }

    if (remoteExecutor != null) {
      remoteExecutor.close();
    }
  }

  void report(Event evt) {

    synchronized (this) {
      if (reportedErrors.contains(evt.getMessage())) {
        return;
      }
      reportedErrors.add(evt.getMessage());
      reporter.handle(evt);
    }
  }

  private static boolean isScrubbedSpawn(Spawn spawn, @Nullable Scrubber scrubber) {
    return scrubber != null && scrubber.forSpawn(spawn) != null;
  }

  /**
   * A simple value class combining a hash of the tool inputs (and their digests) as well as a set
   * of the relative paths of all tool inputs.
   */
  private static final class ToolSignature {
    private final String key;
    private final Set<PathFragment> toolInputs;

    private ToolSignature(String key, Set<PathFragment> toolInputs) {
      this.key = key;
      this.toolInputs = toolInputs;
    }
  }
}
