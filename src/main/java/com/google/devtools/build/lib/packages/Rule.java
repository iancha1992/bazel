// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper.attributeOrNull;
import static com.google.devtools.build.lib.util.HashCodes.hashObjects;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.StarlarkImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Package.ConfigSettingVisibilityPolicy;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * An instance of a build rule in the build language. A rule has a name, a package to which it
 * belongs, a class such as <code>cc_library</code>, and set of typed attributes. The set of
 * attribute names and types is a property of the rule's class. The use of the term "class" here has
 * nothing to do with Java classes. All rules are implemented by the same Java classes, Rule and
 * RuleClass.
 *
 * <p>Here is a typical rule as it appears in a BUILD file:
 *
 * <pre>
 * cc_library(name = 'foo',
 *            defines = ['-Dkey=value'],
 *            srcs = ['foo.cc'],
 *            deps = ['bar'])
 * </pre>
 */
// Non-final only for mocking in tests. Do not subclass!
public class Rule extends RuleOrMacroInstance implements Target {

  /** Label predicate that allows every label. */
  public static final Predicate<Label> ALL_LABELS = Predicates.alwaysTrue();

  private static final OutputFile[] NO_OUTPUTS = new OutputFile[0];

  public static final String IS_EXECUTABLE_ATTRIBUTE_NAME = "$is_executable";

  private final Packageoid pkg;
  private final RuleClass ruleClass;
  private final Location location;
  @Nullable private final CallStack.Node interiorCallStack;

  /**
   * Output files generated by this rule.
   *
   * <p>To save memory, this field is either {@link #NO_OUTPUTS} for zero outputs, an {@link
   * OutputFile} for a single output, or an {@code OutputFile[]} for multiple outputs.
   *
   * <p>In the case of multiple outputs, all implicit outputs come before any explicit outputs in
   * the array.
   *
   * <p>The order of the implicit outputs is the same as returned by the implicit output function.
   * This allows a native rule implementation and native implicit outputs function to agree on the
   * index of a given kind of output. The order of explicit outputs preserves the attribute
   * iteration order and the order of values in a list attribute; the latter is important so that
   * {@code ctx.outputs.some_list} has a well-defined order.
   */
  // Initialized by populateOutputFilesInternal().
  private Object outputFiles;

  Rule(
      Packageoid pkg,
      Label label,
      RuleClass ruleClass,
      Location location,
      @Nullable CallStack.Node interiorCallStack) {
    super(label, ruleClass.getAttributeProvider().getAttributeCount());
    this.pkg = checkNotNull(pkg);
    this.ruleClass = checkNotNull(ruleClass);
    this.location = checkNotNull(location);
    this.interiorCallStack = interiorCallStack;
  }

  @Override
  public Packageoid getPackageoid() {
    return pkg;
  }

  @Override
  public Package.Metadata getPackageMetadata() {
    return pkg.getMetadata();
  }

  @Override
  public Package.Declarations getPackageDeclarations() {
    return pkg.getDeclarations();
  }

  public RuleClass getRuleClassObject() {
    return ruleClass;
  }

  @Override
  public String getTargetKind() {
    return ruleClass.getTargetKind();
  }

  /** Returns the class of this rule. (e.g. "cc_library") */
  @Override
  public String getRuleClass() {
    return ruleClass.getName();
  }

  /**
   * Returns true iff the outputs of this rule should be created beneath the bin directory, false if
   * beneath genfiles. For most rule classes, this is constant, but for genrule, it is a property of
   * the individual target, derived from the 'output_to_bindir' attribute.
   */
  public boolean outputsToBindir() {
    return ruleClass.getName().equals("genrule") // this is unfortunate...
        ? NonconfigurableAttributeMapper.of(this).get("output_to_bindir", Type.BOOLEAN)
        : ruleClass.outputsToBindir();
  }

  /** Returns true if this rule is an analysis test (set by analysis_test = true). */
  public boolean isAnalysisTest() {
    return ruleClass.isAnalysisTest();
  }

  /**
   * Returns true if this rule has at least one attribute with an analysis test transition. (A
   * starlark-defined transition using analysis_test_transition()).
   */
  public boolean hasAnalysisTestTransition() {
    return ruleClass.hasAnalysisTestTransition();
  }

  public boolean isBuildSetting() {
    return ruleClass.getBuildSetting() != null;
  }

  /**
   * Returns true if this rule is in error.
   *
   * <p>Examples of rule errors include attributes with missing values or values of the wrong type.
   *
   * <p>Any error in a package means that all rules in the package are considered to be in error
   * (even if they were evaluated prior to the error). This policy is arguably stricter than need
   * be, but stopping a build only for some errors but not others creates user confusion.
   */
  public boolean containsErrors() {
    return pkg.containsErrors();
  }

  public boolean hasAspects() {
    return ruleClass.hasAspects();
  }

  /**
   * Returns true if the given attribute is configurable.
   */
  public boolean isConfigurableAttribute(String attributeName) {
    // TODO(murali): This method should be property of ruleclass not rule instance.
    // Further, this call to AbstractAttributeMapper.isConfigurable is delegated right back
    // to this instance!
    return AbstractAttributeMapper.isConfigurable(this, attributeName);
  }

  /**
   * Returns the attribute definition whose name is {@code attrName}, or null if not found. (Use
   * get[X]Attr for the actual value.)
   *
   * @deprecated use {@link AbstractAttributeMapper#getAttributeDefinition} instead
   */
  @Deprecated
  public Attribute getAttributeDefinition(String attrName) {
    return ruleClass.getAttributeProvider().getAttributeByNameMaybe(attrName);
  }

  /**
   * Constructs and returns an immutable list containing all the declared output files of this rule.
   *
   * <p>There are two kinds of outputs. Explicit outputs are declared in attributes of type OUTPUT
   * or OUTPUT_LABEL. Implicit outputs are determined by custom rule logic in an "implicit outputs
   * function" (either defined natively or in Starlark), and are named following a template pattern
   * based on the target's attributes.
   *
   * <p>All implicit output files (declared in the {@link RuleClass}) are listed first, followed by
   * any explicit files (declared via output attributes). Additionally, both implicit and explicit
   * outputs will retain the relative order in which they were declared.
   */
  public ImmutableList<OutputFile> getOutputFiles() {
    return ImmutableList.copyOf(outputFilesArray());
  }

  /**
   * Constructs and returns an immutable list of all the implicit output files of this rule, in the
   * order they were declared.
   */
  ImmutableList<OutputFile> getImplicitOutputFiles() {
    ImmutableList.Builder<OutputFile> result = ImmutableList.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        break;
      }
      result.add(output);
    }
    return result.build();
  }

  /**
   * Constructs and returns an immutable multimap of the explicit outputs, from attribute name to
   * associated value.
   *
   * <p>Keys are listed in the same order as attributes. Order of attribute values (outputs in an
   * output list) is preserved.
   *
   * <p>Since this is a multimap, attributes that have no associated outputs are omitted from the
   * result.
   */
  public ImmutableListMultimap<String, OutputFile> getExplicitOutputFileMap() {
    ImmutableListMultimap.Builder<String, OutputFile> result = ImmutableListMultimap.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        result.put(output.getOutputKey(), output);
      }
    }
    return result.build();
  }

  /**
   * Returns a map of the Starlark-defined implicit outputs, from dict key to output file.
   *
   * <p>If there is no implicit outputs function, or it is a native one, an empty map is returned.
   *
   * <p>This is not a multimap because Starlark-defined implicit output functions return exactly one
   * output per key.
   */
  public ImmutableMap<String, OutputFile> getStarlarkImplicitOutputFileMap() {
    if (!(ruleClass.getDefaultImplicitOutputsFunction()
        instanceof StarlarkImplicitOutputsFunction)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, OutputFile> result = ImmutableMap.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        break;
      }
      result.put(output.getOutputKey(), output);
    }
    return result.buildOrThrow();
  }

  private OutputFile[] outputFilesArray() {
    return outputFiles instanceof OutputFile outputFile
        ? new OutputFile[] {outputFile}
        : (OutputFile[]) outputFiles;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  /**
   * Returns the stack of function calls active when this rule was instantiated.
   *
   * <p>Requires reconstructing the call stack from a compact representation, so should only be
   * called when the full call stack is needed.
   */
  public ImmutableList<StarlarkThread.CallStackEntry> reconstructCallStack() {
    ImmutableList.Builder<StarlarkThread.CallStackEntry> stack = ImmutableList.builder();
    stack.add(StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, location));
    for (CallStack.Node node = interiorCallStack; node != null; node = node.next()) {
      stack.add(node.toCallStackEntry());
    }
    return stack.build();
  }

  @Nullable
  CallStack.Node getInteriorCallStack() {
    return interiorCallStack;
  }

  @Override
  public Rule getAssociatedRule() {
    return this;
  }

  /*
   *******************************************************************
   * Attribute accessor functions.
   *
   * The below provide access to attribute definitions and other generic
   * metadata.
   *
   * For access to attribute *values* (e.g. "What's the value of attribute
   * X for Rule Y?"), go through {@link RuleContext#attributes}. If no
   * RuleContext is available, create a localized {@link AbstractAttributeMapper}
   * instance instead.
   *******************************************************************
   */

  @Nullable
  private String getRelativeLocation() {
    // Determining the workspace root only works reliably if both location and label point to files
    // in the same package.
    // It would be preferable to construct the path from the label itself, but this doesn't work for
    // rules created from function calls in a subincluded file, even if both files share a path
    // prefix (for example, when //a/package:BUILD subincludes //a/package/with/a/subpackage:BUILD).
    // We can revert to that approach once subincludes aren't supported anymore.
    //
    // TODO(b/151165647): this logic has always been wrong:
    // it spuriously matches occurrences of the package name earlier in the path.
    String absolutePath = location.toString();
    int pos = absolutePath.indexOf(label.getPackageName());
    return (pos < 0) ? null : absolutePath.substring(pos);
  }

  /**
   * Returns the value of the attribute with the given index. Returns null, if no such attribute
   * exists OR no value was set.
   */
  @Override
  @Nullable
  Object getAttrWithIndex(int attrIndex) {
    Object value = getAttrIfStored(attrIndex);
    if (value != null) {
      return value;
    }
    Attribute attr = ruleClass.getAttributeProvider().getAttribute(attrIndex);
    if (attr.hasComputedDefault()) {
      // Frozen rules don't store computed defaults, so get it from the attribute. Mutable rules do
      // store computed defaults if they've been populated. If no value is stored for a mutable
      // rule, return null here since resolving the default could trigger reads of other attributes
      // which have not yet been populated. Note that in this situation returning null does not
      // result in a correctness issue, since the value for the attribute is actually a function to
      // compute the value.
      return isFrozen() ? attr.getDefaultValue(this) : null;
    }
    if (attr.isMaterializing()) {
      checkState(isFrozen(), "Mutable rule missing LateBoundDefault");
      return attr.getMaterializer();
    }
    if (attr.isLateBound()) {
      // Frozen rules don't store late bound defaults.
      checkState(isFrozen(), "Mutable rule missing LateBoundDefault");
      return attr.getLateBoundDefault();
    }
    return switch (attr.getName()) {
      case GENERATOR_FUNCTION -> interiorCallStack != null ? interiorCallStack.functionName() : "";
      case GENERATOR_LOCATION -> interiorCallStack != null ? getRelativeLocation() : "";
      case GENERATOR_NAME ->
          generatorNamePrefixLength > 0 ? getName().substring(0, generatorNamePrefixLength) : "";
      default -> attr.getDefaultValue(this);
    };
  }

  @Override
  public boolean isRuleInstance() {
    return true;
  }

  @Override
  public boolean isRuleCreatedInMacro() {
    // TODO(bazel-team): do we really need the `hasStringAttribute(GENERATOR_NAME)` check?
    return interiorCallStack != null || hasStringAttribute(GENERATOR_NAME);
  }

  /** Returns the macro that generated this rule, or an empty string. */
  public String getGeneratorFunction() {
    Object value = getAttr(GENERATOR_FUNCTION);
    if (value instanceof String valString) {
      return valString;
    }
    return "";
  }

  private boolean hasStringAttribute(String attrName) {
    Object value = getAttr(attrName);
    if (value instanceof String valString) {
      return !valString.isEmpty();
    }
    return false;
  }

  /**
   * Returns a new list containing all direct dependencies (all types except outputs and nodeps).
   */
  public List<Label> getLabels() {
    List<Label> labels = new ArrayList<>();
    AggregatingAttributeMapper.of(this).visitAllLabels((attribute, label) -> labels.add(label));
    return labels;
  }

  /**
   * Returns a sorted set containing all labels that match a given {@link DependencyFilter}, not
   * including outputs.
   *
   * @param filter A dependency filter that determines whether a label should be included in the
   *     result. {@link DependencyFilter#test} is called with this rule and the attribute that
   *     contains the label. The label will be contained in the result iff the predicate returns
   *     {@code true} <em>and</em> the label is not an output.
   */
  public ImmutableSortedSet<Label> getSortedLabels(DependencyFilter filter) {
    ImmutableSortedSet.Builder<Label> labels = ImmutableSortedSet.naturalOrder();
    AggregatingAttributeMapper.of(this)
        .visitLabels(filter, (Attribute attribute, Label label) -> labels.add(label));
    return labels.build();
  }

  /**
   * Returns a {@link SetMultimap} containing all non-output labels matching a given {@link
   * DependencyFilter}, keyed by the corresponding attribute.
   *
   * <p>Labels that appear in multiple attributes will be mapped from each of their corresponding
   * attributes, provided they pass the {@link DependencyFilter}.
   *
   * @param filter A dependency filter that determines whether a label should be included in the
   *     result. {@link DependencyFilter#test} is called with this rule and the attribute that
   *     contains the label. The label will be contained in the result iff the predicate returns
   *     {@code true} <em>and</em> the label is not an output.
   */
  public SetMultimap<Attribute, Label> getTransitions(DependencyFilter filter) {
    SetMultimap<Attribute, Label> transitions = HashMultimap.create();
    AggregatingAttributeMapper.of(this).visitLabels(filter, transitions::put);
    return transitions;
  }

  /**
   * Collects the output files (both implicit and explicit). Must be called before the output
   * accessors methods can be used, and must be called only once.
   */
  void populateOutputFiles(EventHandler eventHandler, PackageIdentifier pkgId)
      throws LabelSyntaxException, InterruptedException {
    populateOutputFilesInternal(
        eventHandler,
        pkgId,
        ruleClass.getDefaultImplicitOutputsFunction(),
        /* checkLabels= */ true);
  }

  void populateOutputFilesUnchecked(
      TargetDefinitionContext targetDefinitionContext,
      ImplicitOutputsFunction implicitOutputsFunction)
      throws InterruptedException {
    try {
      populateOutputFilesInternal(
          NullEventHandler.INSTANCE,
          targetDefinitionContext.getPackageIdentifier(),
          implicitOutputsFunction,
          /* checkLabels= */ false);
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @FunctionalInterface
  private interface ExplicitOutputHandler {
    void accept(Attribute attribute, Label outputLabel) throws LabelSyntaxException;
  }

  @FunctionalInterface
  private interface ImplicitOutputHandler {
    void accept(String outputKey, String outputName);
  }

  private void populateOutputFilesInternal(
      EventHandler eventHandler,
      PackageIdentifier pkgId,
      ImplicitOutputsFunction implicitOutputsFunction,
      boolean checkLabels)
      throws LabelSyntaxException, InterruptedException {
    Preconditions.checkState(outputFiles == null);

    List<OutputFile> outputs = new ArrayList<>();
    // Detects collisions where the same output key is used for both an explicit and implicit entry.
    HashSet<String> implicitOutputKeys = new HashSet<>();

    // We need the implicits to appear before the explicits in the final data structure, so we
    // process them first. We check for duplicates while handling the explicits.
    //
    // Each of these cases has two subcases, so we factor their bodies out into lambdas.

    ImplicitOutputHandler implicitOutputHandler =
        // outputKey: associated dict key if Starlark-defined, empty string otherwise
        // outputName: package-relative path fragment
        (outputKey, outputName) -> {
          Label label;
          if (checkLabels) { // controls label syntax validation only
            try {
              label = Label.create(pkgId, outputName);
            } catch (LabelSyntaxException e) {
              reportError(
                  String.format(
                      "illegal output file name '%s' in rule %s due to: %s",
                      outputName, this.label, e.getMessage()),
                  eventHandler);
              return;
            }
          } else {
            label = Label.createUnvalidated(pkgId, outputName);
          }
          validateOutputLabel(label, eventHandler);

          outputs.add(OutputFile.createImplicit(label, this, outputKey));
          implicitOutputKeys.add(outputKey);
        };

    // Populate the implicit outputs.
    try {
      RawAttributeMapper attributeMap = RawAttributeMapper.of(this);
      // TODO(bazel-team): Reconsider the ImplicitOutputsFunction abstraction. It doesn't seem to be
      // a good fit if it forces us to downcast in situations like this. It also causes
      // getImplicitOutputs() to declare that it throws EvalException (which then has to be
      // explicitly disclaimed by the subclass SafeImplicitOutputsFunction).
      if (implicitOutputsFunction instanceof StarlarkImplicitOutputsFunction) {
        for (Map.Entry<String, String> e :
            ((StarlarkImplicitOutputsFunction) implicitOutputsFunction)
                .calculateOutputs(eventHandler, attributeMap)
                .entrySet()) {
          implicitOutputHandler.accept(e.getKey(), e.getValue());
        }
      } else {
        for (String out : implicitOutputsFunction.getImplicitOutputs(eventHandler, attributeMap)) {
          implicitOutputHandler.accept(/*outputKey=*/ "", out);
        }
      }
    } catch (EvalException e) {
      reportError(String.format("In rule %s: %s", label, e.getMessageWithStack()), eventHandler);
    }

    ExplicitOutputHandler explicitOutputHandler =
        (attribute, outputLabel) -> {
          String attrName = attribute.getName();
          if (implicitOutputKeys.contains(attrName)) {
            reportError(
                String.format(
                    "Implicit output key '%s' collides with output attribute name", attrName),
                eventHandler);
          }
          if (checkLabels) {
            if (!outputLabel.getPackageIdentifier().equals(pkg.getPackageIdentifier())) {
              throw new IllegalStateException(
                  String.format(
                      "Label for attribute %s should refer to '%s' but instead refers to '%s'"
                          + " (label '%s')",
                      attribute,
                      pkg.getMetadata().getName(),
                      outputLabel.getPackageFragment(),
                      outputLabel.getName()));
            }
            if (outputLabel.getName().equals(".")) {
              throw new LabelSyntaxException("output file name can't be equal '.'");
            }
          }
          validateOutputLabel(outputLabel, eventHandler);

          outputs.add(OutputFile.createExplicit(outputLabel, this, attrName));
        };

    // Populate the explicit outputs.
    NonconfigurableAttributeMapper nonConfigurableAttributes =
        NonconfigurableAttributeMapper.of(this);
    for (Attribute attribute : ruleClass.getAttributeProvider().getAttributes()) {
      String name = attribute.getName();
      Type<?> type = attribute.getType();
      if (type == BuildType.OUTPUT) {
        Label label = nonConfigurableAttributes.get(name, BuildType.OUTPUT);
        if (label != null) {
          explicitOutputHandler.accept(attribute, label);
        }
      } else if (type == BuildType.OUTPUT_LIST) {
        for (Label label : nonConfigurableAttributes.get(name, BuildType.OUTPUT_LIST)) {
          explicitOutputHandler.accept(attribute, label);
        }
      }
    }

    if (outputs.isEmpty()) {
      outputFiles = NO_OUTPUTS;
    } else if (outputs.size() == 1) {
      outputFiles = outputs.get(0);
    } else {
      outputFiles = outputs.toArray(OutputFile[]::new);
    }
  }

  private void validateOutputLabel(Label label, EventHandler eventHandler) {
    if (label.getName().equals(getName())) {
      // TODO(bazel-team): for now (23 Apr 2008) this is just a warning.  After
      // June 1st we should make it an error.
      reportWarning("target '" + getName() + "' is both a rule and a file; please choose "
                    + "another name for the rule", eventHandler);
    }
  }

  /**
   * Marks the rule's package or package piece as in error, and propagates the error message to the
   * reporter.
   *
   * <p>This method may only be called while the rule's package or package piece is being
   * constructed.
   */
  @Override
  void reportError(String message, EventHandler eventHandler) {
    eventHandler.handle(Package.error(location, message, PackageLoading.Code.STARLARK_EVAL_ERROR));
    pkg.setContainsErrors();
  }

  private void reportWarning(String message, EventHandler eventHandler) {
    eventHandler.handle(Event.warn(location, message));
  }

  /** Returns a string of the form "cc_binary rule //foo:foo" */
  @Override
  public String toString() {
    return getRuleClass() + " rule " + label;
  }

  /**
   * Implementation of {@link #getRawVisibility} that avoids constructing a {@code RuleVisibility}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private List<Label> getRawVisibilityLabels() {
    Integer visibilityIndex = ruleClass.getAttributeProvider().getAttributeIndex("visibility");
    if (visibilityIndex == null) {
      return null;
    }
    return (List<Label>) getAttrIfStored(visibilityIndex);
  }

  @Override
  @Nullable
  public RuleVisibility getRawVisibility() {
    List<Label> rawLabels = getRawVisibilityLabels();
    // The attribute value was already validated when it was set, so call the unchecked method.
    return rawLabels != null ? RuleVisibility.parseUnchecked(rawLabels) : null;
  }

  /**
   * Retrieves the package's default visibility, or for certain rule classes, injects a different
   * default visibility.
   */
  @Override
  public RuleVisibility getDefaultVisibility() {
    if (ruleClass.getName().equals("bind")) {
      return RuleVisibility.PUBLIC; // bind rules are always public.
    }
    // Temporary logic to relax config_setting's visibility enforcement while depot migrations set
    // visibility settings properly (legacy code may have visibility settings that would break if
    // enforced). See https://github.com/bazelbuild/bazel/issues/12669. Ultimately this entire
    // conditional should be removed.
    if (ruleClass.getName().equals("config_setting")
        && pkg.getMetadata().configSettingVisibilityPolicy()
            == ConfigSettingVisibilityPolicy.DEFAULT_PUBLIC) {
      return RuleVisibility.PUBLIC; // Default: //visibility:public.
    }

    return Target.super.getDefaultVisibility();
  }

  @Override
  public Iterable<Label> getVisibilityDependencyLabels() {
    List<Label> rawLabels = getRawVisibilityLabels();
    if (rawLabels == null) {
      return getDefaultVisibility().getDependencyLabels();
    }
    RuleVisibility constantVisibility = RuleVisibility.parseIfConstant(rawLabels);
    if (constantVisibility != null) {
      return constantVisibility.getDependencyLabels();
    }
    // Filter out labels like :__pkg__ and :__subpackages__.
    return Iterables.filter(rawLabels, label -> PackageSpecification.fromLabel(label) == null);
  }


  @Override
  public boolean isConfigurable() {
    return true;
  }

  @Override
  public License getLicense() {
    // New style licenses defined by Starlark rules don't
    // have old-style licenses. This is hardcoding the representation
    // of new-style rules, but it's in the old-style licensing code path
    // and will ultimately be removed.
    if (ruleClass.isPackageMetadataRule()) {
      return License.NO_LICENSE;
    } else if (isAttrDefined("licenses", BuildType.LICENSE)
        && isAttributeValueExplicitlySpecified("licenses")) {
      return NonconfigurableAttributeMapper.of(this).get("licenses", BuildType.LICENSE);
    } else if (ruleClass.ignoreLicenses()) {
      return License.NO_LICENSE;
    } else {
      return getPackageDeclarations().getPackageArgs().license();
    }
  }

  /**
   * Returns the license of the output of the binary created by this rule, or null if it is not
   * specified.
   */
  @Nullable
  public License getToolOutputLicense(AttributeMap attributes) {
    if (isAttrDefined("output_licenses", BuildType.LICENSE)
        && attributes.isAttributeValueExplicitlySpecified("output_licenses")) {
      return attributes.get("output_licenses", BuildType.LICENSE);
    } else {
      return null;
    }
  }

  /** Returns the Set of all tags exhibited by this target. May be empty. */
  @Override
  public Set<String> getRuleTags() {
    Set<String> ruleTags = new LinkedHashSet<>();
    for (Attribute attribute : ruleClass.getAttributeProvider().getAttributes()) {
      if (attribute.isTaggable()) {
        Type<?> attrType = attribute.getType();
        String name = attribute.getName();
        // This enforces the expectation that taggable attributes are non-configurable.
        Object value = NonconfigurableAttributeMapper.of(this).get(name, attrType);
        Set<String> tags = attrType.toTagSet(value, name);
        ruleTags.addAll(tags);
      }
    }
    return ruleTags;
  }

  /** Returns only the `tags` attribute value. */
  public ImmutableList<String> getOnlyTagsAttribute() {
    Attribute tagsAttribute = ruleClass.getAttributeProvider().getAttributeByName("tags");
    Type<?> attrType = tagsAttribute.getType();
    String name = tagsAttribute.getName();
    // This enforces the expectation that taggable attributes are non-configurable.
    Object value = NonconfigurableAttributeMapper.of(this).get(name, attrType);
    return ImmutableList.copyOf(attrType.toTagSet(value, name));
  }

  @Override
  public AttributeProvider getAttributeProvider() {
    return ruleClass.getAttributeProvider();
  }

  @Override
  public boolean isRule() {
    return true;
  }

  @Override
  @Nullable
  public String getDeprecationWarning() {
    return attributeOrNull(this, "deprecation", Type.STRING);
  }

  @Override
  public boolean isTestOnly() {
    Boolean value = attributeOrNull(this, "testonly", Type.BOOLEAN);
    if (value == null) {
      return false;
    }
    return value;
  }

  @Override
  public boolean satisfies(RequiredProviders required) {
    return required.isSatisfiedBy(getRuleClassObject().getAdvertisedProviders());
  }

  @Override
  public TestTimeout getTestTimeout() {
    return TestTimeout.getTestTimeout(this);
  }

  @Override
  public boolean isForDependencyResolution() {
    return getRuleClassObject().isDependencyResolutionRule();
  }

  @Override
  public AdvertisedProviderSet getAdvertisedProviders() {
    return getRuleClassObject().getAdvertisedProviders();
  }

  /**
   * Computes labels of additional dependencies that can be provided by aspects that this rule can
   * require from its direct dependencies.
   */
  public Collection<Label> getAspectLabelsSuperset(DependencyFilter predicate) {
    if (!hasAspects()) {
      return ImmutableList.of();
    }
    SetMultimap<Attribute, Label> labels = LinkedHashMultimap.create();
    for (Attribute attribute : this.getAttributes()) {
      for (Aspect candidateClass : attribute.getAspects(this)) {
        AspectDefinition.addAllAttributesOfAspect(labels, candidateClass, predicate);
      }
    }
    return labels.values();
  }

  /**
   * Should this rule instance resolve toolchains?
   *
   * <p>This may happen for two reasons:
   *
   * <ol>
   *   <li>The rule uses toolchains by definition ({@link
   *       RuleClass.Builder#toolchainResolutionMode(ToolchainResolutionMode)}
   *   <li>The rule instance has a select() or target_compatible_with attribute, which means it may
   *       depend on target platform properties that are only provided when toolchain resolution is
   *       enabled.
   * </ol>
   */
  public boolean useToolchainResolution() {
    return ruleClass.useToolchainResolution(this);
  }

  public boolean isExecutable() {
    if (getRuleClassObject()
        .getAttributeProvider()
        .hasAttr(IS_EXECUTABLE_ATTRIBUTE_NAME, Type.BOOLEAN)) {
      return NonconfigurableAttributeMapper.of(this)
          .get(IS_EXECUTABLE_ATTRIBUTE_NAME, Type.BOOLEAN);
    }
    return false;
  }

  public RepositoryName getRepository() {
    return label.getPackageIdentifier().getRepository();
  }

  /** Returns the suffix of target kind for all rules. */
  public static String targetKindSuffix() {
    return " rule";
  }

  @Override
  public TargetData reduceForSerialization() {
    return new RuleData(
        ruleClass,
        getLocation(),
        ImmutableSet.copyOf(getRuleTags()),
        getLabel(),
        getDeprecationWarning(),
        isTestOnly(),
        getTestTimeout());
  }

  @VisibleForSerialization // (private) allows RuleDataCodec visibility
  static class RuleData implements TargetData {
    private final RuleClassData ruleClassData;
    private final Location location;
    // TODO(b/297857068): this is only used to report TargetCompletion, so it should never be
    // read from a deserialized instance. Refine the ConfiguredTargetAndData API and delete this.
    private final ImmutableSet<String> ruleTags;
    private final Label label;
    @Nullable private final String deprecationWarning;
    private final boolean isTestOnly;
    @Nullable private final TestTimeout testTimeout;

    @VisibleForSerialization // (private) allows RuleDataCodec visibility
    RuleData(
        RuleClassData ruleClassData,
        Location location,
        ImmutableSet<String> ruleTags,
        Label label,
        @Nullable String deprecationWarning,
        boolean isTestOnly,
        @Nullable TestTimeout testTimeout) {
      this.ruleClassData = ruleClassData;
      this.location = location;
      this.ruleTags = ruleTags;
      this.label = label;
      this.deprecationWarning = deprecationWarning;
      this.isTestOnly = isTestOnly;
      this.testTimeout = testTimeout;
    }

    RuleClassData getRuleClassData() {
      return ruleClassData;
    }

    @Override
    public String getTargetKind() {
      return ruleClassData.getTargetKind();
    }

    @Override
    public Location getLocation() {
      return location;
    }

    @Override
    public String getRuleClass() {
      return ruleClassData.getName();
    }

    @Override
    public ImmutableSet<String> getRuleTags() {
      return ruleTags;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    @Override
    public boolean isRule() {
      return true;
    }

    @Override
    @Nullable
    public String getDeprecationWarning() {
      return deprecationWarning;
    }

    @Override
    public boolean satisfies(RequiredProviders required) {
      return required.isSatisfiedBy(ruleClassData.getAdvertisedProviders());
    }

    @Override
    public boolean isTestOnly() {
      return isTestOnly;
    }

    @Override
    public boolean isForDependencyResolution() {
      return ruleClassData.isDependencyResolutionRule();
    }

    @Override
    public AdvertisedProviderSet getAdvertisedProviders() {
      return ruleClassData.getAdvertisedProviders();
    }

    @Override
    @Nullable
    public TestTimeout getTestTimeout() {
      return testTimeout;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RuleData that)) {
        return false;
      }
      return ruleClassData.equals(that.ruleClassData)
          && location.equals(that.location)
          && label.equals(that.label)
          && Objects.equals(deprecationWarning, that.deprecationWarning)
          && isTestOnly == that.isTestOnly
          && Objects.equals(testTimeout, that.testTimeout);
    }

    @Override
    public int hashCode() {
      // Extremely likely equal if this many fields match.
      return hashObjects(ruleClassData, location, label);
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("ruleClassData", ruleClassData)
          .add("location", location)
          .add("ruleTags", ruleTags)
          .add("label", label)
          .add("deprecationWarning", deprecationWarning)
          .add("isTestOnly", isTestOnly)
          .add("testTimeout", testTimeout)
          .toString();
    }
  }
}
