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

package com.google.devtools.build.lib.starlarkbuildapi.java;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkActionFactoryApi;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkRuleContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintValueInfoApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Utilities for Java compilation support in Starlark. */
@StarlarkBuiltin(
    name = "java_common",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc = "Utilities for Java compilation support in Starlark.")
public interface JavaCommonApi<
        FileT extends FileApi,
        JavaInfoT extends JavaInfoApi<FileT, ?, ?>,
        JavaToolchainT extends JavaToolchainStarlarkApiProviderApi,
        BootClassPathT extends ProviderApi,
        ConstraintValueT extends ConstraintValueInfoApi,
        StarlarkRuleContextT extends StarlarkRuleContextApi<ConstraintValueT>,
        StarlarkActionFactoryT extends StarlarkActionFactoryApi>
    extends StarlarkValue {

  @StarlarkMethod(
      name = "compile",
      doc =
          "Compiles Java source files/jars from the implementation of a Starlark rule and returns "
              + "a provider that represents the results of the compilation and can be added to "
              + "the set of providers emitted by this rule.",
      parameters = {
        @Param(name = "ctx", positional = true, named = false, doc = "The rule context."),
        @Param(
            name = "source_jars",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of the jars to be compiled. At least one of source_jars or source_files"
                    + " should be specified."),
        @Param(
            name = "source_files",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of the Java source files to be compiled. At least one of source_jars or "
                    + "source_files should be specified."),
        @Param(name = "output", positional = false, named = true),
        @Param(
            name = "output_source_jar",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The output source jar. Optional. Defaults to `{output_jar}-src.jar` if unset."),
        @Param(
            name = "javac_opts",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc = "A list of the desired javac options. Optional."),
        @Param(
            name = "deps",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            defaultValue = "[]",
            doc = "A list of dependencies. Optional."),
        @Param(
            name = "runtime_deps",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            defaultValue = "[]",
            doc = "A list of runtime dependencies. Optional."),
        @Param(
            name = "exports",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class),
            },
            defaultValue = "[]",
            doc = "A list of exports. Optional."),
        @Param(
            name = "plugins",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaPluginInfoApi.class),
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)
            },
            defaultValue = "[]",
            doc = "A list of plugins. Optional."),
        @Param(
            name = "exported_plugins",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = JavaPluginInfoApi.class),
              @ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)
            },
            defaultValue = "[]",
            doc = "A list of exported plugins. Optional."),
        @Param(
            name = "native_libraries",
            positional = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = CcInfoApi.class)},
            named = true,
            defaultValue = "[]",
            doc = "CC native library dependencies that are needed for this library."),
        @Param(
            name = "annotation_processor_additional_inputs",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of inputs that the Java compilation action will take in addition to the "
                    + "Java sources for annotation processing."),
        @Param(
            name = "annotation_processor_additional_outputs",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]",
            doc =
                "A list of outputs that the Java compilation action will output in addition to "
                    + "the class jar from annotation processing."),
        @Param(
            name = "strict_deps",
            defaultValue = "'ERROR'",
            positional = false,
            named = true,
            doc =
                "A string that specifies how to handle strict deps. Possible values: 'OFF', "
                    + "'ERROR', 'WARN' and 'DEFAULT'. For more details see "
                    + "${link user-manual#flag--strict_java_deps}. By default 'ERROR'."),
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            doc = "A JavaToolchainInfo to be used for this compilation. Mandatory."),
        @Param(
            name = "bootclasspath",
            positional = false,
            named = true,
            defaultValue = "None",
            doc =
                "A BootClassPathInfo to be used for this compilation. If present, overrides the"
                    + " bootclasspath associated with the provided java_toolchain. Optional."),
        @Param(
            name = "host_javabase",
            positional = false,
            named = true,
            doc =
                "Deprecated: You can drop this parameter (host_javabase is provided with "
                    + "java_toolchain)",
            defaultValue = "None",
            disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_JAVA_COMMON_PARAMETERS,
            valueWhenDisabled = "None"),
        @Param(
            name = "sourcepath",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "resources",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "resource_jars",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(
            name = "classpath_resources",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            defaultValue = "[]"),
        @Param(name = "neverlink", positional = false, named = true, defaultValue = "False"),
        @Param(
            name = "enable_annotation_processing",
            positional = false,
            named = true,
            defaultValue = "True",
            doc =
                "Disables annotation processing in this compilation, causing any annotation"
                    + " processors provided in plugins or in exported_plugins of deps to be"
                    + " ignored."),
        @Param(
            name = "enable_compile_jar_action",
            positional = false,
            named = true,
            defaultValue = "True",
            doc =
                "Enables header compilation or ijar creation. If set to False, it forces use of the"
                    + " full class jar in the compilation classpaths of any dependants. Doing so is"
                    + " intended for use by non-library targets such as binaries that do not have"
                    + " dependants."),
        @Param(
            name = "enable_jspecify",
            positional = false,
            named = true,
            defaultValue = "True",
            documented = false),
        @Param(
            name = "include_compilation_info",
            positional = false,
            named = true,
            defaultValue = "True",
            documented = false),
        @Param(
            name = "injecting_rule_kind",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            }),
        @Param(
            name = "add_exports",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc = "Allow this library to access the given <module>/<package>. Optional."),
        @Param(
            name = "add_opens",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            doc =
                "Allow this library to reflectively access the given <module>/<package>."
                    + " Optional."),
      },
      useStarlarkThread = true)
  JavaInfoT createJavaCompileAction(
      StarlarkRuleContextT starlarkRuleContext,
      Sequence<?> sourceJars, // <FileT> expected.
      Sequence<?> sourceFiles, // <FileT> expected.
      FileT outputJar,
      Object outputSourceJar,
      Sequence<?> javacOpts, // <String> expected.
      Sequence<?> deps, // <JavaInfoT> expected.
      Sequence<?> runtimeDeps, // <JavaInfoT> expected.
      Sequence<?> exports, // <JavaInfoT> expected.
      Sequence<?> plugins, // <JavaInfoT> expected.
      Sequence<?> exportedPlugins, // <JavaInfoT> expected.
      Sequence<?> nativeLibraries, // <CcInfoT> expected.
      Sequence<?> annotationProcessorAdditionalInputs, // <FileT> expected.
      Sequence<?> annotationProcessorAdditionalOutputs, // <FileT> expected.
      String strictDepsMode,
      JavaToolchainT javaToolchain,
      Object bootClassPath,
      Object hostJavabase,
      Sequence<?> sourcepathEntries, // <FileT> expected.
      Sequence<?> resources, // <FileT> expected.
      Sequence<?> resourceJars, // <FileT> expected.
      Sequence<?> classpathResources, // <FileT> expected.
      Boolean neverlink,
      Boolean enableAnnotationProcessing,
      Boolean enableCompileJarAction,
      Boolean enableJSpecify,
      boolean includeCompilationInfo,
      Object injectingRuleKind,
      Sequence<?> addExports, // <String> expected.
      Sequence<?> addOpens, // <String> expected.
      StarlarkThread thread)
      throws EvalException, InterruptedException, RuleErrorException, LabelSyntaxException;

  @StarlarkMethod(
      name = "default_javac_opts",
      // This function is experimental for now.
      documented = false,
      parameters = {
        @Param(
            name = "java_toolchain",
            positional = false,
            named = true,
            allowedTypes = {@ParamType(type = JavaToolchainStarlarkApiProviderApi.class)},
            doc =
                "A JavaToolchainInfo to be used for retrieving the ijar "
                    + "tool. Only set when use_ijar is True."),
      })
  // TODO(b/78512644): migrate callers to passing explicit javacopts or using custom toolchains, and
  // delete
  ImmutableList<String> getDefaultJavacOpts(JavaToolchainT javaToolchain) throws EvalException;

  @StarlarkMethod(
      name = "merge",
      doc = "Merges the given providers into a single JavaInfo.",
      parameters = {
        @Param(
            name = "providers",
            positional = true,
            named = false,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = JavaInfoApi.class)},
            doc = "The list of providers to merge."),
        @Param(
            name = "merge_java_outputs",
            positional = false,
            named = true,
            defaultValue = "True"),
        @Param(name = "merge_source_jars", positional = false, named = true, defaultValue = "True"),
      },
      useStarlarkThread = true)
  JavaInfoT mergeJavaProviders(
      Sequence<?> providers /* <JavaInfoT> expected. */,
      boolean mergeJavaOutputs,
      boolean mergeSourceJars,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "JavaToolchainInfo",
      doc =
          "The key used to retrieve the provider that contains information about the Java "
              + "toolchain being used.",
      structField = true)
  ProviderApi getJavaToolchainProvider();

  @StarlarkMethod(
      name = "JavaRuntimeInfo",
      doc =
          "The key used to retrieve the provider that contains information about the Java "
              + "runtime being used.",
      structField = true)
  ProviderApi getJavaRuntimeProvider();

  @StarlarkMethod(
      name = "BootClassPathInfo",
      doc = "The provider used to supply bootclasspath information",
      structField = true)
  ProviderApi getBootClassPathInfo();

  /** Returns target kind. */
  @StarlarkMethod(
      name = "target_kind",
      parameters = {
        @Param(name = "target", positional = true, named = false, doc = "The target."),
        @Param(
            name = "dereference_aliases",
            positional = false,
            named = true,
            defaultValue = "False",
            documented = false),
      },
      documented = false,
      useStarlarkThread = true)
  String getTargetKind(Object target, boolean dereferenceAliases, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "get_build_info",
      documented = false,
      parameters = {@Param(name = "ctx"), @Param(name = "is_stamping_enabled")},
      useStarlarkThread = true)
  Sequence<FileT> getBuildInfo(
      StarlarkRuleContextT ruleContext, boolean isStampingEnabled, StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "experimental_java_proto_library_default_has_services",
      documented = false,
      useStarlarkSemantics = true,
      structField = true,
      doc = "Default value of java_proto_library.has_services")
  boolean getExperimentalJavaProtoLibraryDefaultHasServices(StarlarkSemantics starlarkSemantics)
      throws EvalException;

  @StarlarkMethod(
      name = "collect_native_deps_dirs",
      parameters = {@Param(name = "libraries")},
      useStarlarkThread = true,
      documented = false)
  Sequence<String> collectNativeLibsDirs(Depset libraries, StarlarkThread thread)
      throws EvalException, RuleErrorException, TypeException;

  @StarlarkMethod(
      name = "get_runtime_classpath_for_archive",
      parameters = {@Param(name = "jars"), @Param(name = "excluded_jars")},
      useStarlarkThread = true,
      documented = false)
  Depset getRuntimeClasspathForArchive(
      Depset runtimeClasspath, Depset excludedArtifacts, StarlarkThread thread)
      throws EvalException, TypeException;

  @StarlarkMethod(
      name = "check_provider_instances",
      documented = false,
      parameters = {
        @Param(name = "providers"),
        @Param(name = "what"),
        @Param(name = "provider_type")
      },
      useStarlarkThread = true)
  void checkProviderInstances(
      Sequence<?> providers, String what, ProviderApi providerType, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(name = "_google_legacy_api_enabled", documented = false, useStarlarkThread = true)
  boolean isLegacyGoogleApiEnabled(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "_check_java_toolchain_is_declared_on_rule",
      documented = false,
      parameters = {@Param(name = "actions")},
      useStarlarkThread = true)
  void checkJavaToolchainIsDeclaredOnRuleForStarlark(
      StarlarkActionFactoryT actions, StarlarkThread thread)
      throws EvalException, LabelSyntaxException;

  @StarlarkMethod(
      name = "_incompatible_depset_for_java_output_source_jars",
      documented = false,
      useStarlarkThread = true)
  boolean isDepsetForJavaOutputSourceJarsEnabled(StarlarkThread thread) throws EvalException;
}
