// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.rewinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.flogger.GoogleLogger;
import com.google.common.graph.MutableGraph;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.remote.common.LostInputsEvent;
import com.google.devtools.build.lib.server.FailureDetails.ActionRewinding;
import com.google.devtools.build.lib.server.FailureDetails.ActionRewinding.Code;
import com.google.devtools.build.lib.skyframe.ActionUtils;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.ArtifactDependencies;
import com.google.devtools.build.lib.skyframe.SkyframeActionExecutor;
import com.google.devtools.build.lib.skyframe.TopLevelActionLookupKeyWrapper;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.ActionDescription;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.ActionRewindEvent;
import com.google.devtools.build.lib.skyframe.proto.ActionRewind.LostInput;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindException.FallbackToBuildRewindingException;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindException.GenericActionRewindException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Reset;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Given an action that failed to execute because of lost inputs which were generated by other
 * actions, finds the actions which generated them and the set of Skyframe nodes which must be
 * rewound in order to recreate the lost inputs.
 */
public final class ActionRewindStrategy {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  @VisibleForTesting static final int MAX_REPEATED_LOST_INPUTS = 20;
  @VisibleForTesting static final int MAX_ACTION_REWIND_EVENTS = 5;
  private static final int MAX_LOST_INPUTS_RECORDED = 5;

  private final SkyframeActionExecutor skyframeActionExecutor;
  private final BugReporter bugReporter;

  private ConcurrentHashMultiset<LostInputRecord> currentBuildLostInputRecords =
      ConcurrentHashMultiset.create();
  private ConcurrentHashMultiset<LostInputRecord> currentBuildLostOutputRecords =
      ConcurrentHashMultiset.create();
  private final List<ActionRewindEvent> rewindEventSamples =
      Collections.synchronizedList(new ArrayList<>(MAX_ACTION_REWIND_EVENTS));
  private final AtomicInteger rewindEventSampleCounter = new AtomicInteger(0);

  public ActionRewindStrategy(
      SkyframeActionExecutor skyframeActionExecutor, BugReporter bugReporter) {
    this.skyframeActionExecutor = checkNotNull(skyframeActionExecutor);
    this.bugReporter = checkNotNull(bugReporter);
  }

  /**
   * Returns a {@link Reset} specifying the Skyframe nodes to rewind to recreate lost outputs
   * observed by a top-level completion function.
   *
   * <p>Also prepares {@link SkyframeActionExecutor} for the rewind plan.
   *
   * @throws ActionRewindException if rewinding is disabled, or if any lost outputs have been seen
   *     by {@code failedKey} as lost before too many times
   */
  public Reset prepareRewindPlanForLostTopLevelOutputs(
      TopLevelActionLookupKeyWrapper failedKey,
      Set<SkyKey> failedKeyDeps,
      ImmutableMap<String, ActionInput> lostOutputsByDigest,
      LostInputOwners owners,
      Environment env)
      throws ActionRewindException, InterruptedException {
    checkRewindingEnabled(lostOutputsByDigest, LostType.OUTPUT, env.getListener());

    ImmutableList<LostInputRecord> lostOutputRecords =
        checkIfTopLevelOutputLostTooManyTimes(failedKey, lostOutputsByDigest);

    ImmutableList.Builder<Action> depsToRewind = ImmutableList.builder();
    Reset rewindPlan;
    try (var ignored =
        AutoProfiler.profiled(
            "Preparing rewind plan for %d lost outputs of %s"
                .formatted(lostOutputRecords.size(), failedKey.actionLookupKey().getLabel()),
            ProfilerTask.ACTION_REWINDING)) {
      rewindPlan =
          prepareRewindPlan(
              failedKey, failedKeyDeps, lostOutputsByDigest, owners, env, depsToRewind);
    }

    if (shouldRecordRewindEventSample()) {
      rewindEventSamples.add(createLostOutputRewindEvent(failedKey, rewindPlan, lostOutputRecords));
    }

    for (Action dep : depsToRewind.build()) {
      skyframeActionExecutor.prepareDepForRewinding(failedKey, dep);
    }
    return rewindPlan;
  }

  /**
   * Returns a {@link Reset} specifying the Skyframe nodes to rewind to recreate the lost inputs
   * specified by {@code lostInputsException}.
   *
   * <p>Also prepares {@link SkyframeActionExecutor} for the rewind plan and emits an {@link
   * ActionRewoundEvent} if necessary.
   *
   * @throws ActionRewindException if rewinding is disabled, or if any lost inputs have been seen by
   *     {@code failedKey} as lost before too many times
   */
  public Reset prepareRewindPlanForLostInputs(
      ActionLookupData failedKey,
      Action failedAction,
      Set<SkyKey> failedActionDeps,
      LostInputsActionExecutionException e,
      InputMetadataProvider metadataProvider,
      Environment env,
      long actionStartTimeNanos)
      throws ActionRewindException, InterruptedException {
    ImmutableMap<String, ActionInput> lostInputsByDigest = e.getLostInputs();
    checkRewindingEnabled(lostInputsByDigest, LostType.INPUT, env.getListener());

    ImmutableList<LostInputRecord> lostInputRecords =
        checkIfActionLostInputTooManyTimes(failedKey, failedAction, lostInputsByDigest);
    LostInputOwners owners =
        e.getOwners()
            .orElseGet(
                () -> calculateLostInputOwners(lostInputsByDigest.values(), metadataProvider));

    ImmutableList.Builder<Action> depsToRewind = ImmutableList.builder();
    Reset rewindPlan;
    try (var ignored =
        AutoProfiler.profiled(
            "Preparing rewind plan for %d lost inputs of %s"
                .formatted(lostInputRecords.size(), failedAction.prettyPrint()),
            ProfilerTask.ACTION_REWINDING)) {
      rewindPlan =
          prepareRewindPlan(
              failedKey, failedActionDeps, lostInputsByDigest, owners, env, depsToRewind);
    }

    if (shouldRecordRewindEventSample()) {
      rewindEventSamples.add(
          createLostInputRewindEvent(failedAction, rewindPlan, lostInputRecords));
    }

    if (e.isActionStartedEventAlreadyEmitted()) {
      env.getListener()
          .post(new ActionRewoundEvent(actionStartTimeNanos, BlazeClock.nanoTime(), failedAction));
    }
    skyframeActionExecutor.prepareForRewinding(failedKey, failedAction, depsToRewind.build());
    return rewindPlan;
  }

  private enum LostType {
    INPUT("inputs", Code.LOST_INPUT_REWINDING_DISABLED),
    OUTPUT("outputs", Code.LOST_OUTPUT_REWINDING_DISABLED);

    private final String description;
    private final Code codeWhenDisabled;

    LostType(String description, Code codeWhenDisabled) {
      this.description = description;
      this.codeWhenDisabled = codeWhenDisabled;
    }
  }

  private void checkRewindingEnabled(
      ImmutableMap<String, ActionInput> lostArtifacts,
      LostType lostType,
      ExtendedEventHandler listener)
      throws ActionRewindException {
    if (skyframeActionExecutor.rewindingEnabled()) {
      return;
    }
    if (skyframeActionExecutor.invocationRetriesEnabled()) {
      // If rewinding failed, Bazel may still be able to recover by retrying the invocation in
      // BlazeCommandDispatcher if retries are enabled. This requires emitting an event to inform
      // Bazel's remote module of the lost inputs.
      listener.post(new LostInputsEvent(lostArtifacts.keySet()));
      throw new FallbackToBuildRewindingException(
          lostArtifacts.entrySet().stream()
              .limit(MAX_LOST_INPUTS_RECORDED)
              .map(lost -> "%s (%s)".formatted(prettyPrint(lost.getValue()), lost.getKey()))
              .collect(
                  joining(
                      ", ",
                      "Lost %s no longer available remotely: ".formatted(lostType.description),
                      "")));
    }
    throw new GenericActionRewindException(
        "Unexpected lost %s (pass --rewind_lost_inputs to enable recovery): %s"
            .formatted(lostType.description, prettyPrint(lostArtifacts.values())),
        lostType.codeWhenDisabled);
  }

  private Reset prepareRewindPlan(
      SkyKey failedKey,
      Set<SkyKey> failedKeyDeps,
      ImmutableMap<String, ActionInput> lostInputsByDigest,
      LostInputOwners owners,
      Environment env,
      ImmutableList.Builder<Action> depsToRewind)
      throws InterruptedException {
    ImmutableList<ActionInput> lostInputs = lostInputsByDigest.values().asList();

    // This graph tracks which Skyframe nodes must be rewound and the dependency relationships
    // between them.
    MutableGraph<SkyKey> rewindGraph = Reset.newRewindGraphFor(failedKey);

    Set<DerivedArtifact> lostArtifacts =
        getLostInputOwningDirectDeps(failedKey, failedKeyDeps, lostInputs, owners);

    // Additional nested sets we may need to invalidate that are the dependencies of an
    // insensitively propagating action, associated with the key that depends on them.
    SetMultimap<SkyKey, ArtifactNestedSetKey> nestedSetsForPropagatingActions =
        HashMultimap.create();

    for (DerivedArtifact lostArtifact : lostArtifacts) {
      Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(lostArtifact, env);
      if (actionMap == null) {
        // Some deps of the artifact are not done. Another rewind must be in-flight, and there is no
        // need to rewind the shared deps twice.
        continue;
      }
      ImmutableList<ActionAndLookupData> newlyVisitedActions =
          addArtifactDepsAndGetNewlyVisitedActions(rewindGraph, lostArtifact, actionMap);

      // Note that Artifact.key(lostArtifact) must be rewound. We do this after
      // addArtifactDepsAndGetNewlyVisitedActions so that it can track if actions are already known
      // to be in the graph. It is possible that the Artifact.key() is not actually a direct dep of
      // the action if it is below an ArtifactNestedSetKey, but this edge is benign since it's
      // always a transitive dep.
      rewindGraph.putEdge(failedKey, Artifact.key(lostArtifact));
      depsToRewind.addAll(actions(newlyVisitedActions));
      checkActions(
          newlyVisitedActions, env, rewindGraph, depsToRewind, nestedSetsForPropagatingActions);
    }

    // addNestedSetPathsToRewindGraph, called after this loop, stops its walk when it finds a node
    // that is already in the rewind graph.
    // However, because this rewinds all NestedSet chains from a given root, early termination later
    // on won't matter because all dependent NestedSets are already in the graph.
    // This may seem excessive, but it is not expected that many NestedSets are actually involved in
    // this walk, and that this only happens rarely.
    // TODO(b/395634488): This should be solved in a more elegant way, but a solution is needed to
    // unblock the simplifications to Fileset (b/394611260)
    for (SkyKey rootKey : nestedSetsForPropagatingActions.keySet()) {
      for (ArtifactNestedSetKey nestedSetKey : nestedSetsForPropagatingActions.get(rootKey)) {
        ArtifactNestedSetKey.addNestedSetChainsToRewindGraph(rewindGraph, nestedSetKey);
        rewindGraph.putEdge(rootKey, nestedSetKey);
      }
    }

    // This needs to be done after the loop above because addArtifactDepsAndGetNewlyVisitedActions
    // short-circuits when a node is already in the rewind graph.
    ArtifactNestedSetKey.addNestedSetPathsToRewindGraph(
        rewindGraph, failedKey, failedKeyDeps, lostArtifacts);

    return Reset.of(rewindGraph);
  }

  /**
   * Creates a {@link Reset} to recover from undone indirect inputs that are unavailable due to
   * unsuccessful rewinding.
   *
   * <p>Undone direct dependencies are handled by Skyframe (see {@link
   * com.google.devtools.build.skyframe.SkyFunctionEnvironment.UndonePreviouslyRequestedDeps}). This
   * method only exists for artifacts whose {@link Artifact#key} is <em>not</em> a direct dependency
   * of {@code failedKey} because the artifact is behind an {@link ArtifactNestedSetKey}.
   *
   * <p>Used when an indirect dependency {@link Artifact#key} was rewound and completed with an
   * error, but intermediate nested set nodes were never rewound, resulting in an inconsistent state
   * where successful nodes depend on a node in error.
   *
   * <p>The returned {@link Reset} contains the {@link Artifact#key}, but that is expected to
   * already be in error, and attempting to rewind an error is no-op.
   */
  public Reset patchNestedSetGraphToPropagateError(
      ActionLookupData failedKey,
      Action failedAction,
      ImmutableSet<Artifact> undoneInputs,
      ImmutableSet<SkyKey> failedActionDeps) {
    checkState(
        skyframeActionExecutor.rewindingEnabled(), "Unexpected undone inputs: %s", undoneInputs);
    MutableGraph<SkyKey> rewindGraph = Reset.newRewindGraphFor(failedKey);
    ArtifactNestedSetKey.addNestedSetPathsToRewindGraph(
        rewindGraph, failedKey, failedActionDeps, undoneInputs);
    for (Artifact input : undoneInputs) {
      checkState(
          rewindGraph.nodes().contains(Artifact.key(input)),
          "Cannot find input %s under any nested set deps of %s",
          input,
          failedKey);
    }

    // An undone input may be observed either during input checking (before attempting action
    // execution) or during lost input handling (after attempting action execution). In the latter
    // case, it is necessary to obsolete the ActionExecutionState so that after rewinding, we will
    // check inputs again and discover the propagated exception. This call is a no-op in the former
    // case, since there is no ActionExecutionState to obsolete.
    skyframeActionExecutor.prepareForRewinding(
        failedKey, failedAction, /* depsToRewind= */ ImmutableList.of());

    return Reset.of(rewindGraph);
  }

  /**
   * Logs the first N action rewind events and clears the history of failed actions' lost inputs and
   * rewind plans.
   */
  public void reset(ExtendedEventHandler eventHandler) {
    ActionRewindingStats rewindingStats =
        new ActionRewindingStats(
            currentBuildLostInputRecords.size(),
            currentBuildLostOutputRecords.size(),
            ImmutableList.copyOf(rewindEventSamples));
    eventHandler.post(rewindingStats);
    currentBuildLostInputRecords = ConcurrentHashMultiset.create();
    currentBuildLostOutputRecords = ConcurrentHashMultiset.create();
    rewindEventSamples.clear();
    rewindEventSampleCounter.set(0);
  }

  private ImmutableList<LostInputRecord> checkIfTopLevelOutputLostTooManyTimes(
      TopLevelActionLookupKeyWrapper failedKey,
      ImmutableMap<String, ActionInput> lostOutputsByDigest)
      throws ActionRewindException {
    ImmutableList<LostInputRecord> lostOutputRecords =
        createLostInputRecords(failedKey, lostOutputsByDigest);
    for (LostInputRecord lostInputRecord : lostOutputRecords) {
      String digest = lostInputRecord.lostInputDigest();
      int losses = currentBuildLostOutputRecords.add(lostInputRecord, /* occurrences= */ 1) + 1;
      if (losses > MAX_REPEATED_LOST_INPUTS) {
        ActionInput output = lostOutputsByDigest.get(digest);
        ActionRewindException e =
            new GenericActionRewindException(
                String.format(
                    "Lost output %s (digest %s), and rewinding was ineffective after %d attempts.",
                    prettyPrint(output), digest, MAX_REPEATED_LOST_INPUTS),
                ActionRewinding.Code.LOST_OUTPUT_TOO_MANY_TIMES);
        bugReporter.sendBugReport(e);
        throw e;
      } else if (losses > 1) {
        logger.atWarning().log(
            "Lost output again (losses=%s, output=%s, digest=%s, failedKey=%s)",
            losses, lostOutputsByDigest.get(digest), digest, failedKey);
      }
    }
    return lostOutputRecords;
  }

  private static String prettyPrint(Collection<ActionInput> inputs) {
    return inputs.stream().map(ActionRewindStrategy::prettyPrint).collect(joining(","));
  }

  private static String prettyPrint(ActionInput input) {
    return input instanceof Artifact a ? a.prettyPrint() : input.getExecPathString();
  }

  /** Returns all lost input records that will cause the failed action to rewind. */
  private ImmutableList<LostInputRecord> checkIfActionLostInputTooManyTimes(
      ActionLookupData failedKey,
      Action failedAction,
      ImmutableMap<String, ActionInput> lostInputsByDigest)
      throws ActionRewindException {
    ImmutableList<LostInputRecord> lostInputRecords =
        createLostInputRecords(failedKey, lostInputsByDigest);
    for (LostInputRecord lostInputRecord : lostInputRecords) {
      // The same action losing the same input more than once is unexpected [*]. The action should
      // have waited until the depended-on action which generates the lost input is (re)run before
      // trying again.
      //
      // Note that we could enforce a stronger check: if action A, which depends on an input N
      // previously detected as lost (by any action, not just A), discovers that N is still lost,
      // and action A started after the re-evaluation of N's generating action, then something has
      // gone wrong. Administering that check would be more complex (e.g., the start/completion
      // times of actions would need tracking), so we punt on it for now.
      //
      // [*], TODO(b/123993876): To mitigate a race condition (believed to be) caused by
      // non-topological Skyframe dirtying of depended-on nodes, this check fails the build only if
      // the same input is repeatedly lost.
      String digest = lostInputRecord.lostInputDigest();
      int losses = currentBuildLostInputRecords.add(lostInputRecord, /* occurrences= */ 1) + 1;
      if (losses > MAX_REPEATED_LOST_INPUTS) {
        // This ensures coalesced shared actions aren't orphaned.
        skyframeActionExecutor.prepareForRewinding(
            failedKey, failedAction, /* depsToRewind= */ ImmutableList.of());

        String message =
            String.format(
                "lost input too many times (#%s) for the same action. lostInput: %s, "
                    + "lostInput digest: %s, failedAction: %.10000s",
                losses, lostInputsByDigest.get(digest), digest, failedAction);
        ActionRewindException e =
            new GenericActionRewindException(
                message, ActionRewinding.Code.LOST_INPUT_TOO_MANY_TIMES);
        bugReporter.sendBugReport(e);
        throw e;
      } else if (losses > 1) {
        logger.atInfo().log(
            "lost input again (#%s) for the same action. lostInput: %s, "
                + "lostInput digest: %s, failedAction: %.10000s",
            losses, lostInputsByDigest.get(digest), digest, failedAction);
      }
    }
    return lostInputRecords;
  }

  private static ImmutableList<LostInputRecord> createLostInputRecords(
      SkyKey failedKey, ImmutableMap<String, ActionInput> lostInputsByDigest) {
    return lostInputsByDigest.entrySet().stream()
        .map(e -> LostInputRecord.create(failedKey, e.getKey(), e.getValue().getExecPathString()))
        .collect(toImmutableList());
  }

  /**
   * Calculates the {@link LostInputOwners} for {@code lostInputs}.
   *
   * <p>This is only necessary when {@link LostInputsActionExecutionException#getOwners} is not
   * present.
   */
  public static LostInputOwners calculateLostInputOwners(
      ImmutableCollection<ActionInput> lostInputs, InputMetadataProvider inputArtifactData) {
    Set<ActionInput> lostInputsAndOwners = new HashSet<>();
    LostInputOwners owners = new LostInputOwners();
    for (ActionInput lostInput : lostInputs) {
      lostInputsAndOwners.add(lostInput);
      if (lostInput instanceof Artifact artifact && artifact.hasParent()) {
        lostInputsAndOwners.add(artifact.getParent());
        owners.addOwner(artifact, artifact.getParent());
      }
    }

    inputArtifactData
        .getFilesets()
        .forEach(
            (fileset, outputTree) -> {
              for (FilesetOutputSymlink link : outputTree.symlinks()) {
                if (lostInputsAndOwners.contains(link.target())) {
                  lostInputsAndOwners.add(fileset);
                  owners.addOwner(link.target(), fileset);
                }
              }
            });

    // Runfiles trees may contain tree artifacts and filesets, but not vice versa. Runfiles are
    // processed last to ensure that any lost input owning tree artifacts and filesets are already
    // in lostInputsAndOwners.
    for (RunfilesTree runfilesTree : inputArtifactData.getRunfilesTrees()) {
      Artifact runfilesArtifact =
          (Artifact) inputArtifactData.getInput(runfilesTree.getExecPath().getPathString());
      checkState(runfilesArtifact.isRunfilesTree(), runfilesArtifact);

      RunfilesArtifactValue runfilesValue = inputArtifactData.getRunfilesMetadata(runfilesArtifact);
      for (Artifact artifact : runfilesValue.getAllArtifacts()) {
        if (lostInputsAndOwners.contains(artifact)) {
          owners.addOwner(artifact, runfilesArtifact);
        }
      }
    }

    return owners;
  }

  private Set<DerivedArtifact> getLostInputOwningDirectDeps(
      SkyKey failedKey,
      Set<SkyKey> failedKeyDeps,
      ImmutableList<ActionInput> lostInputs,
      LostInputOwners owners) {
    // Not all input artifacts' keys are direct deps - they may be below an ArtifactNestedSetKey.
    // Expand all ArtifactNestedSetKey deps to get a flat set with all input artifact keys.
    Set<SkyKey> expandedDeps = new HashSet<>();
    for (SkyKey dep : failedKeyDeps) {
      if (dep instanceof ArtifactNestedSetKey nestedSetDep) {
        expandedDeps.addAll(Artifact.keys(nestedSetDep.expandToArtifacts()));
      } else {
        expandedDeps.add(dep);
      }
    }

    Set<DerivedArtifact> lostInputOwningDirectDeps = new HashSet<>();
    for (ActionInput lostInput : lostInputs) {
      boolean foundLostInputDepOwner = false;

      ImmutableSet<Artifact> directOwners = owners.getOwners(lostInput);
      for (Artifact directOwner : directOwners) {
        checkDerived(directOwner);

        // Rewinding must invalidate all Skyframe paths from the failed action to the action which
        // generates the lost input. Intermediate nodes not on the shortest path to that action may
        // have values that depend on the output of that action. If these intermediate nodes are not
        // invalidated, then their values may become stale. Therefore, this method collects not only
        // the first action dep associated with the lost input, but all of them.

        ImmutableSet<Artifact> transitiveOwners = owners.getOwners(directOwner);
        for (Artifact transitiveOwner : transitiveOwners) {
          checkDerived(transitiveOwner);

          if (expandedDeps.contains(Artifact.key(transitiveOwner))) {
            // The lost input is included in an aggregation artifact (e.g. a tree artifact or
            // fileset) that is included by an aggregation artifact (e.g. a runfiles tree) that the
            // action directly depends on.
            lostInputOwningDirectDeps.add((DerivedArtifact) transitiveOwner);
            foundLostInputDepOwner = true;
          }
        }

        if (expandedDeps.contains(Artifact.key(directOwner))) {
          // The lost input is included in an aggregation artifact (e.g. a tree artifact, fileset,
          // or runfiles tree) that the action directly depends on.
          lostInputOwningDirectDeps.add((DerivedArtifact) directOwner);
          foundLostInputDepOwner = true;
        }
      }

      if (lostInput instanceof Artifact artifact && expandedDeps.contains(Artifact.key(artifact))) {
        checkDerived(artifact);

        lostInputOwningDirectDeps.add((DerivedArtifact) lostInput);
        foundLostInputDepOwner = true;
      }

      if (!foundLostInputDepOwner) {
        // Rewinding can't do anything about a lost input that can't be associated with a direct dep
        // of the failed action. In this case, try resetting the failed action (and no other deps)
        // just in case that helps. If it does not help, then eventually the action will fail in
        // checkIfActionLostInputTooManyTimes.
        bugReporter.sendNonFatalBugReport(
            new IllegalStateException(
                String.format(
                    "Lost input not a dep of the failed action and can't be associated with such"
                        + " a dep. lostInput: %s, owners: %s, failedKey: %s",
                    lostInput, owners, failedKey)));
      }
    }
    return lostInputOwningDirectDeps;
  }

  private static void checkDerived(Artifact artifact) {
    checkState(!artifact.isSourceArtifact(), "Unexpected source artifact: %s", artifact);
  }

  /**
   * Looks at each action in {@code actionsToCheck} and determines whether additional artifacts or
   * actions need to be rewound. If this finds more actions to rewind, those actions are recursively
   * checked too.
   */
  private void checkActions(
      ImmutableList<ActionAndLookupData> actionsToCheck,
      Environment env,
      MutableGraph<SkyKey> rewindGraph,
      ImmutableList.Builder<Action> depsToRewind,
      SetMultimap<SkyKey, ArtifactNestedSetKey> nestedSetDeps)
      throws InterruptedException {
    ArrayDeque<ActionAndLookupData> uncheckedActions = new ArrayDeque<>(actionsToCheck);
    while (!uncheckedActions.isEmpty()) {
      ActionAndLookupData actionAndLookupData = uncheckedActions.removeFirst();
      ActionLookupData actionKey = actionAndLookupData.lookupData();
      Action action = actionAndLookupData.action();
      ArrayList<DerivedArtifact> artifactsToCheck = new ArrayList<>();
      ArrayList<ActionLookupData> newlyDiscoveredActions = new ArrayList<>();

      if (action.mayInsensitivelyPropagateInputs()) {
        // Rewinding this action won't recreate the missing input. We need to also rewind this
        // action's non-source inputs and the actions which created those inputs.
        addPropagatingActionDepsAndGetNewlyVisitedArtifactsAndActions(
            rewindGraph,
            actionKey,
            action,
            artifactsToCheck,
            newlyDiscoveredActions,
            nestedSetDeps);
      }

      for (ActionLookupData actionLookupData : newlyDiscoveredActions) {
        Action additionalAction =
            checkNotNull(
                ActionUtils.getActionForLookupData(
                    env, actionLookupData, /* crashIfActionOwnerMissing= */ true),
                actionLookupData);
        depsToRewind.add(additionalAction);
        uncheckedActions.add(ActionAndLookupData.create(actionLookupData, additionalAction));
      }
      for (DerivedArtifact artifact : artifactsToCheck) {
        Map<ActionLookupData, Action> actionMap = getActionsForLostArtifact(artifact, env);
        if (actionMap == null) {
          continue;
        }
        ImmutableList<ActionAndLookupData> newlyVisitedActions =
            addArtifactDepsAndGetNewlyVisitedActions(rewindGraph, artifact, actionMap);
        depsToRewind.addAll(actions(newlyVisitedActions));
        uncheckedActions.addAll(newlyVisitedActions);
      }
    }
  }

  /**
   * For a propagating {@code action} with key {@code actionKey}, add its generated inputs' keys to
   * {@code rewindGraph}, add edges from {@code actionKey} to those keys, add any {@link Artifact}s
   * to {@code newlyVisitedArtifacts}, and add any {@link ActionLookupData}s to {@code
   * newlyVisitedActions}.
   */
  private void addPropagatingActionDepsAndGetNewlyVisitedArtifactsAndActions(
      MutableGraph<SkyKey> rewindGraph,
      ActionLookupData actionKey,
      Action action,
      ArrayList<DerivedArtifact> newlyVisitedArtifacts,
      ArrayList<ActionLookupData> newlyVisitedActions,
      SetMultimap<SkyKey, ArtifactNestedSetKey> nestedSetDeps) {

    for (Artifact input : action.getInputs().toList()) {
      if (input.isSourceArtifact()) {
        continue;
      }
      SkyKey artifactKey = Artifact.key(input);
      // Rewinding all derived inputs of propagating actions is overkill. Preferably, we'd want to
      // only rewind the inputs which correspond to the known lost outputs. The information to do
      // this is probably present in the data available to #prepareRewindPlan.
      //
      // Rewinding is expected to be rare, so refining this may not be necessary.
      boolean newlyVisited = rewindGraph.addNode(artifactKey);
      if (newlyVisited) {
        if (artifactKey instanceof Artifact) {
          newlyVisitedArtifacts.add((DerivedArtifact) artifactKey);
        } else if (artifactKey instanceof ActionLookupData actionLookupData) {
          newlyVisitedActions.add(actionLookupData);
        }
      }
      rewindGraph.putEdge(actionKey, artifactKey);
    }

    // Record the action's NestedSet inputs as dependencies to be rewound.
    action
        .getInputs()
        .getNonLeaves()
        .forEach(nestedSet -> nestedSetDeps.put(actionKey, ArtifactNestedSetKey.create(nestedSet)));

    // Rewinding ignores artifacts returned by Action#getAllowedDerivedInputs because:
    // 1) the set of actions with non-throwing implementations of getAllowedDerivedInputs,
    // 2) the set of actions that "mayInsensitivelyPropagateInputs",
    // should have no overlap. Log a bug report if we see such an action:
    if (action.discoversInputs()) {
      bugReporter.sendBugReport(
          new IllegalStateException(
              String.format(
                  "Action insensitively propagates and discovers inputs. actionKey: %s, action: "
                      + "%.10000s",
                  actionKey, action)));
    }
  }

  /**
   * For an artifact {@code artifact} with generating actions (and their associated {@link
   * ActionLookupData}) {@code actionMap}, add those actions' keys to {@code rewindGraph} and add
   * edges from {@code artifact} to those keys.
   *
   * <p>Returns a list of key+action pairs for each action whose key was newly added to the graph.
   */
  private static ImmutableList<ActionAndLookupData> addArtifactDepsAndGetNewlyVisitedActions(
      MutableGraph<SkyKey> rewindGraph,
      Artifact artifact,
      Map<ActionLookupData, Action> actionMap) {

    ImmutableList.Builder<ActionAndLookupData> newlyVisitedActions =
        ImmutableList.builderWithExpectedSize(actionMap.size());
    SkyKey artifactKey = Artifact.key(artifact);
    for (Map.Entry<ActionLookupData, Action> actionEntry : actionMap.entrySet()) {
      ActionLookupData actionKey = actionEntry.getKey();
      if (rewindGraph.addNode(actionKey)) {
        newlyVisitedActions.add(ActionAndLookupData.create(actionKey, actionEntry.getValue()));
      }
      if (!artifactKey.equals(actionKey)) {
        rewindGraph.putEdge(artifactKey, actionKey);
      }
    }
    return newlyVisitedActions.build();
  }

  /**
   * Returns the map of {@code lostInput}'s execution-phase dependencies (i.e. generating actions),
   * keyed by their {@link ActionLookupData} keys, or {@code null} if any of those dependencies are
   * not done.
   */
  @Nullable
  private static Map<ActionLookupData, Action> getActionsForLostArtifact(
      DerivedArtifact lostInput, Environment env) throws InterruptedException {
    Set<ActionLookupData> actionExecutionDeps = getActionExecutionDeps(lostInput, env);
    if (actionExecutionDeps == null) {
      return null;
    }

    Map<ActionLookupData, Action> actions =
        Maps.newHashMapWithExpectedSize(actionExecutionDeps.size());
    for (ActionLookupData dep : actionExecutionDeps) {
      actions.put(
          dep,
          checkNotNull(
              ActionUtils.getActionForLookupData(env, dep, /* crashIfActionOwnerMissing= */ true)));
    }
    return actions;
  }

  /**
   * Returns the set of {@code lostInput}'s execution-phase dependencies (i.e. generating actions),
   * or {@code null} if any of those dependencies are not done.
   */
  @Nullable
  private static ImmutableSet<ActionLookupData> getActionExecutionDeps(
      DerivedArtifact lostInput, Environment env) throws InterruptedException {
    if (!lostInput.isTreeArtifact()) {
      return ImmutableSet.of(lostInput.getGeneratingActionKey());
    }
    ArtifactDependencies artifactDependencies =
        ArtifactDependencies.discoverDependencies(
            lostInput, env, /* crashIfActionOwnerMissing= */ true);
    if (artifactDependencies == null) {
      return null;
    }

    if (!artifactDependencies.isTemplateActionForTreeArtifact()) {
      return ImmutableSet.of(lostInput.getGeneratingActionKey());
    }

    // This ignores the ActionTemplateExpansionKey dependency of the template artifact because we
    // expect to never need to rewind that.
    ImmutableList<ActionLookupData> actionTemplateExpansionKeys =
        artifactDependencies.getActionTemplateExpansionKeys(env);
    if (actionTemplateExpansionKeys == null) {
      return null;
    }
    return ImmutableSet.copyOf(actionTemplateExpansionKeys);
  }

  private boolean shouldRecordRewindEventSample() {
    return rewindEventSampleCounter.getAndIncrement() < MAX_ACTION_REWIND_EVENTS;
  }

  private static ActionRewindEvent createLostOutputRewindEvent(
      TopLevelActionLookupKeyWrapper failedKey,
      Reset rewindPlan,
      ImmutableList<LostInputRecord> lostOutputRecords) {
    return createRewindEventBuilder(rewindPlan, lostOutputRecords)
        .setTopLevelActionLookupKeyDescription(failedKey.actionLookupKey().toString())
        .build();
  }

  private static ActionRewindEvent createLostInputRewindEvent(
      Action failedAction, Reset rewindPlan, ImmutableList<LostInputRecord> lostInputRecords) {
    return createRewindEventBuilder(rewindPlan, lostInputRecords)
        .setActionDescription(
            ActionDescription.newBuilder()
                .setType(failedAction.getMnemonic())
                .setRuleLabel(failedAction.getOwner().getLabel().toString()))
        .build();
  }

  private static ActionRewindEvent.Builder createRewindEventBuilder(
      Reset rewindPlan, ImmutableList<LostInputRecord> lostInputRecords) {
    return ActionRewindEvent.newBuilder()
        .addAllLostInputs(
            lostInputRecords.stream()
                .limit(MAX_LOST_INPUTS_RECORDED)
                .map(
                    lostInputRecord ->
                        LostInput.newBuilder()
                            .setPath(lostInputRecord.lostInputPath())
                            .setDigest(lostInputRecord.lostInputDigest())
                            .build())
                .collect(toImmutableList()))
        .setTotalLostInputsCount(lostInputRecords.size())
        .setInvalidatedNodesCount(rewindPlan.rewindGraph().nodes().size());
  }

  /**
   * A record indicating that {@link #failedKey} failed because it lost an input with the specified
   * digest.
   */
  record LostInputRecord(SkyKey failedKey, String lostInputDigest, String lostInputPath) {
    LostInputRecord {
      requireNonNull(failedKey, "failedKey");
      requireNonNull(lostInputDigest, "lostInputDigest");
      requireNonNull(lostInputPath, "lostInputPath");
    }

    static LostInputRecord create(SkyKey failedKey, String lostInputDigest, String lostInputPath) {
      return new LostInputRecord(failedKey, lostInputDigest, lostInputPath);
    }
  }

  record ActionAndLookupData(ActionLookupData lookupData, Action action) {
    ActionAndLookupData {
      requireNonNull(lookupData, "lookupData");
      requireNonNull(action, "action");
    }

    static ActionAndLookupData create(ActionLookupData lookupData, Action action) {
      return new ActionAndLookupData(lookupData, action);
    }
  }

  private static List<Action> actions(List<ActionAndLookupData> newlyVisitedActions) {
    return Lists.transform(newlyVisitedActions, ActionAndLookupData::action);
  }
}
