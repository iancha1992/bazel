// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.repository;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.BuildFileName;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import javax.annotation.Nullable;

/** Helper class for looking up data from the external package. */
public class ExternalPackageHelper {
  private final ImmutableList<BuildFileName> workspaceFilesByPriority;

  public static final String WORKSPACE_DEPRECATION =
      " Was the repository introduced in WORKSPACE? The WORKSPACE file is disabled by default in"
          + " Bazel 8 (late 2024) and will be removed in Bazel 9 (late 2025), please migrate to"
          + " Bzlmod. See https://bazel.build/external/migration.";

  public ExternalPackageHelper(ImmutableList<BuildFileName> workspaceFilesByPriority) {
    Preconditions.checkArgument(!workspaceFilesByPriority.isEmpty());
    this.workspaceFilesByPriority = workspaceFilesByPriority;
  }

  /** Uses a rule name to fetch the corresponding Rule from the external package. */
  @Nullable
  public Rule getRuleByName(String ruleName, Environment env)
      throws ExternalPackageException, InterruptedException {

    ExternalPackageRuleExtractor extractor = new ExternalPackageRuleExtractor(ruleName);
    if (!iterateWorkspaceFragments(env, extractor)) {
      // Values missing
      return null;
    }

    return extractor.getRule();
  }

  @Nullable
  private static RootedPath checkWorkspaceFile(
      Environment env, Root root, PathFragment workspaceFile) throws InterruptedException {
    RootedPath candidate = RootedPath.toRootedPath(root, workspaceFile);
    FileValue fileValue = (FileValue) env.getValue(FileValue.key(candidate));
    if (env.valuesMissing()) {
      return null;
    }

    return fileValue.isFile() ? candidate : null;
  }

  /**
   * Returns the path of the main WORKSPACE file or null when a Skyframe restart is required.
   *
   * <p>Should also return null when the WORKSPACE file is not present, but then some tests break,
   * so then it lies and returns the RootedPath corresponding to the last package path entry.
   */
  @Nullable
  public RootedPath findWorkspaceFile(Environment env) throws InterruptedException {
    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    ImmutableList<Root> packagePath = packageLocator.getPathEntries();
    for (Root candidateRoot : packagePath) {
      for (BuildFileName workspaceFile : workspaceFilesByPriority) {
        RootedPath path =
            checkWorkspaceFile(env, candidateRoot, workspaceFile.getFilenameFragment());
        if (env.valuesMissing()) {
          return null;
        }

        if (path != null) {
          return path;
        }
      }
    }

    // TODO(lberki): Technically, this means that the WORKSPACE file was not found. I'd love to not
    // have this here, but a lot of tests break without it because they rely on Bazel kinda working
    // even if the WORKSPACE file is not present.
    return RootedPath.toRootedPath(
        Iterables.getLast(packagePath), BuildFileName.WORKSPACE.getFilenameFragment());
  }

  /** Returns WORKSPACE deprecation error message if WORKSPACE file exists. */
  @Nullable
  public String getWorkspaceDeprecationErrorMessage(
      Environment env, boolean workspaceEnabled, boolean isOwnerRepoMainRepo)
      throws InterruptedException {
    // WORKSPACE repo could have only be visible from the main repo.
    if (workspaceEnabled || !isOwnerRepoMainRepo) {
      return "";
    }
    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    ImmutableList<Root> packagePath = packageLocator.getPathEntries();
    for (Root candidateRoot : packagePath) {
      for (BuildFileName workspaceFile :
          ImmutableList.of(
              BuildFileName.WORKSPACE,
              BuildFileName.WORKSPACE_DOT_BAZEL,
              BuildFileName.WORKSPACE_DOT_BZLMOD)) {
        RootedPath path =
            checkWorkspaceFile(env, candidateRoot, workspaceFile.getFilenameFragment());
        if (env.valuesMissing()) {
          return null;
        }

        if (path != null) {
          return WORKSPACE_DEPRECATION;
        }
      }
    }

    return "";
  }

  /** Returns false if some SkyValues were missing. */
  private boolean iterateWorkspaceFragments(Environment env, WorkspaceFileValueProcessor processor)
      throws InterruptedException {
    RootedPath workspacePath = findWorkspaceFile(env);
    if (env.valuesMissing()) {
      return false;
    }

    SkyKey workspaceKey = WorkspaceFileValue.key(workspacePath);
    WorkspaceFileValue value;
    do {
      value = (WorkspaceFileValue) env.getValue(workspaceKey);
      if (value == null) {
        return false;
      }
    } while (processor.processAndShouldContinue(value) && (workspaceKey = value.next()) != null);
    return true;
  }

  private static class ExternalPackageRuleExtractor implements WorkspaceFileValueProcessor {
    private final String ruleName;
    private ExternalPackageException exception;
    private Rule rule;

    private ExternalPackageRuleExtractor(String ruleName) {
      this.ruleName = ruleName;
    }

    @Override
    public boolean processAndShouldContinue(WorkspaceFileValue workspaceFileValue) {
      Package externalPackage = workspaceFileValue.getPackage();
      if (externalPackage.containsErrors()) {
        exception =
            new ExternalPackageException(
                new BuildFileContainsErrorsException(
                    LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER,
                    "Could not load //external package"),
                Transience.PERSISTENT);
        // Stop iteration when encountered errors.
        return false;
      }
      Target target = externalPackage.getTargets().get(ruleName);
      if (target instanceof Rule r) {
        rule = r;
        return false;
      }
      return true;
    }

    public Rule getRule() throws ExternalPackageException {
      if (exception != null) {
        throw exception;
      }
      if (rule == null) {
        throw new ExternalRuleNotFoundException(ruleName);
      }
      return rule;
    }
  }

  private interface WorkspaceFileValueProcessor {
    boolean processAndShouldContinue(WorkspaceFileValue value);
  }
}
