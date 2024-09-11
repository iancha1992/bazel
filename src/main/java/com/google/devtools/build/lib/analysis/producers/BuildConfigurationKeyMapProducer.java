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
package com.google.devtools.build.lib.analysis.producers;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates the needed {@link BuildConfigurationKey} instances for the given options.
 *
 * <p>This includes merging in platform mappings and platform-based flags.
 *
 * <p>The output preserves the iteration order of the input.
 */
public class BuildConfigurationKeyMapProducer
    implements StateMachine, BuildConfigurationKeyProducer.ResultSink<String> {

  /** Interface for clients to accept results of this computation. */
  public interface ResultSink {

    void acceptOptionsParsingError(OptionsParsingException e);

    void acceptPlatformMappingError(PlatformMappingException e);

    void acceptPlatformFlagsError(InvalidPlatformException error);

    void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionedOptions);
  }

  // -------------------- Input --------------------
  private final ResultSink sink;
  private final StateMachine runAfter;
  private final BuildConfigurationKeyCache buildConfigurationKeyCache;
  private final Map<String, BuildOptions> options;

  // -------------------- Internal State --------------------
  // There is only ever a single PlatformMappingValue in use, as the `--platform_mappings` flag
  // can not be changed in a transition.
  private final Map<String, BuildConfigurationKey> results = new HashMap<>();

  public BuildConfigurationKeyMapProducer(
      ResultSink sink,
      StateMachine runAfter,
      BuildConfigurationKeyCache buildConfigurationKeyCache,
      Map<String, BuildOptions> options) {
    this.sink = sink;
    this.buildConfigurationKeyCache = buildConfigurationKeyCache;
    this.runAfter = runAfter;
    this.options = options;
  }

  @Override
  public StateMachine step(Tasks tasks) {
    this.options.entrySet().stream()
        .map(
            entry ->
                new BuildConfigurationKeyProducer<>(
                    (BuildConfigurationKeyProducer.ResultSink<String>) this,
                    StateMachine.DONE,
                    buildConfigurationKeyCache,
                    entry.getKey(),
                    entry.getValue()))
        .forEach(tasks::enqueue);
    return this::combineResults;
  }

  private StateMachine combineResults(Tasks tasks) {
    boolean allPresent =
        this.options.keySet().stream()
            .map(this.results::containsKey)
            .allMatch(contains -> contains);
    if (!allPresent) {
      // An error occurred while processing at least one set of options.
      return StateMachine.DONE;
    }

    // Ensure that the result keys are in the same order as the original.
    ImmutableMap.Builder<String, BuildConfigurationKey> output = new ImmutableMap.Builder<>();
    for (String transitionKey : this.options.keySet()) {
      BuildConfigurationKey resultKey = this.results.get(transitionKey);
      output.put(transitionKey, resultKey);
    }

    this.sink.acceptTransitionedConfigurations(output.buildOrThrow());
    return this.runAfter;
  }

  @Override
  public void acceptOptionsParsingError(OptionsParsingException e) {
    this.sink.acceptOptionsParsingError(e);
  }

  @Override
  public void acceptPlatformMappingError(PlatformMappingException e) {
    this.sink.acceptPlatformMappingError(e);
  }

  @Override
  public void acceptPlatformFlagsError(InvalidPlatformException error) {
    this.sink.acceptPlatformFlagsError(error);
  }

  @Override
  public void acceptTransitionedConfiguration(
      String transitionKey, BuildConfigurationKey transitionedOptionKey) {
    this.results.put(transitionKey, transitionedOptionKey);
  }
}
