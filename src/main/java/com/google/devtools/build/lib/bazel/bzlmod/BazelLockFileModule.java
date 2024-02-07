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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Module collecting Bazel module and module extensions resolution results and updating the
 * lockfile.
 */
public class BazelLockFileModule extends BlazeModule {

  private Path workspaceRoot;
  @Nullable private BazelModuleResolutionEvent moduleResolutionEvent;
  private final Map<ModuleExtensionId, ModuleExtensionResolutionEvent>
      extensionResolutionEventsMap = new HashMap<>();

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  public void beforeCommand(CommandEnvironment env) {
    workspaceRoot = env.getWorkspace();
    RepositoryOptions options = env.getOptions().getOptions(RepositoryOptions.class);
    if (options.lockfileMode.equals(LockfileMode.UPDATE)) {
      env.getEventBus().register(this);
    }
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    if (moduleResolutionEvent == null) {
      // Command does not use Bazel modules or the lockfile mode is not update.
      // Since Skyframe caches events, they are replayed even when nothing has changed.
      Preconditions.checkState(extensionResolutionEventsMap.isEmpty());
      return;
    }

    BazelLockFileValue oldLockfile = moduleResolutionEvent.getOnDiskLockfileValue();
    BazelLockFileValue newLockfile;
    try {
      // Create an updated version of the lockfile, keeping only the extension results from the old
      // lockfile that are still up-to-date and adding the newly resolved extension results.
      newLockfile =
          moduleResolutionEvent.getResolutionOnlyLockfileValue().toBuilder()
              .setModuleExtensions(combineModuleExtensions(oldLockfile))
              .build();
    } catch (ExternalDepsException e) {
      logger.atSevere().withCause(e).log(
          "Failed to read and parse the MODULE.bazel.lock file with error: %s."
              + " Try deleting it and rerun the build.",
          e.getMessage());
      return;
    }

    // Write the new value to the file, but only if needed. This is not just a performance
    // optimization: whenever the lockfile is updated, most Skyframe nodes will be marked as dirty
    // on the next build, which breaks commands such as `bazel config` that rely on
    // com.google.devtools.build.skyframe.MemoizingEvaluator#getDoneValues.
    if (!newLockfile.equals(oldLockfile)) {
      updateLockfile(workspaceRoot, newLockfile);
    }
    this.moduleResolutionEvent = null;
    this.extensionResolutionEventsMap.clear();
  }

  /**
   * Combines the old extensions stored in the lockfile -if they are still valid- with the new
   * extensions from the events (if any)
   */
  private ImmutableMap<
          ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
      combineModuleExtensions(BazelLockFileValue oldLockfile) throws ExternalDepsException {
    Map<ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
        updatedExtensionMap = new HashMap<>();

    // Keep old extensions if they are still valid.
    ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> oldExtensionUsages =
        BazelDepGraphFunction.getExtensionUsagesById(oldLockfile.getModuleDepGraph());
    for (var entry : oldLockfile.getModuleExtensions().entrySet()) {
      var moduleExtensionId = entry.getKey();
      var factorToLockedExtension = entry.getValue();
      ModuleExtensionEvalFactors firstEntryFactors =
          factorToLockedExtension.keySet().iterator().next();
      if (shouldKeepExtension(moduleExtensionId, firstEntryFactors, oldExtensionUsages)) {
        updatedExtensionMap.put(moduleExtensionId, factorToLockedExtension);
      }
    }

    // Add the new resolved extensions
    for (var event : extensionResolutionEventsMap.values()) {
      var oldExtensionEntries = updatedExtensionMap.get(event.getExtensionId());
      ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension> extensionEntries;
      if (oldExtensionEntries != null) {
        // extension exists, add the new entry to the existing map
        extensionEntries =
            new ImmutableMap.Builder<ModuleExtensionEvalFactors, LockFileModuleExtension>()
                .putAll(oldExtensionEntries)
                .put(event.getExtensionFactors(), event.getModuleExtension())
                .buildKeepingLast();
      } else {
        // new extension
        extensionEntries = ImmutableMap.of(event.getExtensionFactors(), event.getModuleExtension());
      }
      updatedExtensionMap.put(event.getExtensionId(), extensionEntries);
    }

    // The order in which extensions are added to extensionResolutionEvents depends on the order
    // in which their Skyframe evaluations finish, which is non-deterministic. We ensure a
    // deterministic lockfile by sorting.
    return ImmutableSortedMap.copyOf(
        updatedExtensionMap, ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
  }

  /**
   * Decide whether to keep this extension or not depending on all of:
   *
   * <ol>
   *   <li>If its dependency on os & arch didn't change
   *   <li>If its usages haven't changed
   * </ol>
   *
   * @param lockedExtensionKey object holding the old extension id and state of os and arch
   * @param oldExtensionUsages the usages of this extension in the existing lockfile
   * @return True if this extension should still be in lockfile, false otherwise
   */
  private boolean shouldKeepExtension(
      ModuleExtensionId extensionId,
      ModuleExtensionEvalFactors lockedExtensionKey,
      ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> oldExtensionUsages) {

    // If there is a new event for this extension, compare it with the existing ones
    ModuleExtensionResolutionEvent extEvent = extensionResolutionEventsMap.get(extensionId);
    if (extEvent != null) {
      boolean dependencyOnOsChanged =
          lockedExtensionKey.getOs().isEmpty() != extEvent.getExtensionFactors().getOs().isEmpty();
      boolean dependencyOnArchChanged =
          lockedExtensionKey.getArch().isEmpty()
              != extEvent.getExtensionFactors().getArch().isEmpty();
      if (dependencyOnOsChanged || dependencyOnArchChanged) {
        return false;
      }
    }

    // Otherwise, compare the current usages of this extension with the ones in the lockfile. We
    // trim the usages to only the information that influences the evaluation of the extension so
    // that irrelevant changes (e.g. locations or imports) don't cause the extension to be removed.
    // Note: Extension results can still be stale for other reasons, e.g. because their transitive
    // bzl hash changed, but such changes will be detected in SingleExtensionEvalFunction.
    return ModuleExtensionUsage.trimForEvaluation(
            moduleResolutionEvent.getExtensionUsagesById().row(extensionId))
        .equals(ModuleExtensionUsage.trimForEvaluation(oldExtensionUsages.row(extensionId)));
  }

  /**
   * Updates the data stored in the lockfile (MODULE.bazel.lock)
   *
   * @param workspaceRoot Root of the workspace where the lockfile is located
   * @param updatedLockfile The updated lockfile data to save
   */
  public static void updateLockfile(Path workspaceRoot, BazelLockFileValue updatedLockfile) {
    RootedPath lockfilePath =
        RootedPath.toRootedPath(Root.fromPath(workspaceRoot), LabelConstants.MODULE_LOCKFILE_NAME);
    try {
      FileSystemUtils.writeContent(
          lockfilePath.asPath(),
          UTF_8,
          GsonTypeAdapterUtil.createLockFileGson(
                      lockfilePath
                          .asPath()
                          .getParentDirectory()
                          .getRelative(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME),
                      workspaceRoot)
                  .toJson(updatedLockfile)
              + "\n");
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Error while updating MODULE.bazel.lock file: %s", e.getMessage());
    }
  }

  @Subscribe
  public void bazelModuleResolved(BazelModuleResolutionEvent moduleResolutionEvent) {
    // Latest event wins, which is relevant in the case of `bazel mod tidy`, where a new event is
    // sent after the command has modified the module file.
    this.moduleResolutionEvent = moduleResolutionEvent;
  }

  @Subscribe
  public void moduleExtensionResolved(ModuleExtensionResolutionEvent extensionResolutionEvent) {
    this.extensionResolutionEventsMap.put(
        extensionResolutionEvent.getExtensionId(), extensionResolutionEvent);
  }
}
