#!/bin/bash
#
# Copyright 2021 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
    || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "${CURRENT_DIR}/coverage_helpers.sh" \
  || { echo "coverage_helpers.sh not found!" >&2; exit 1; }

COVERAGE_GENERATOR_WORKSPACE_FILE="$1"; shift
if [[ "${COVERAGE_GENERATOR_WORKSPACE_FILE}" != "released" ]]; then
  COVERAGE_GENERATOR_DIR="$(dirname "$(rlocation $COVERAGE_GENERATOR_WORKSPACE_FILE)")"
  add_to_bazelrc "build --override_repository=bazel_tools+remote_coverage_tools_extension+remote_coverage_tools=${COVERAGE_GENERATOR_DIR}"
fi

# Returns the path of the code coverage report that was generated by Bazel by
# looking at the current $TEST_log. The method fails if TEST_log does not
# contain any coverage report for a passed test.
function get_coverage_file_path_from_test_log() {
  local ending_part="$(sed -n -e '/PASSED/,$p' "$TEST_log")"

  local coverage_file_path=$(grep -Eo "/[/a-zA-Z0-9+\.\_\-]+\.dat$" <<< "$ending_part")
  [[ -e "$coverage_file_path" ]] || fail "Coverage output file does not exist!"
  echo "$coverage_file_path"
}

function set_up() {
    touch WORKSPACE
}

function test_starlark_rule_without_lcov_merger() {
    cat <<EOF > rules.bzl
def _impl(ctx):
    output = ctx.actions.declare_file(ctx.attr.name)
    ctx.actions.write(output, """\
#!/bin/bash

if [[ ! -r extra ]]; then
  echo "extra file not found" >&2
  exit 1
fi

if [[ -z \$COVERAGE ]]; then
  echo "COVERAGE environment variable not set, coverage not run."
  exit 1
fi
""", is_executable = True)
    extra_file = ctx.actions.declare_file("extra")
    ctx.actions.write(extra_file, "extra")
    return [DefaultInfo(executable=output, runfiles=ctx.runfiles(files=[extra_file]))]

custom_test = rule(
    implementation = _impl,
    test = True,
)
EOF

    cat <<EOF > BUILD
load(":rules.bzl", "custom_test")

custom_test(name = "foo_test")
EOF
    bazel coverage //:foo_test --combined_report=lcov > $TEST_log \
        || fail "Coverage run failed but should have succeeded."
}

function test_starlark_rule_without_lcov_merger_failing_test() {
    cat <<EOF > rules.bzl
def _impl(ctx):
    executable = ctx.actions.declare_file(ctx.attr.name)
    ctx.actions.write(executable, "exit 1", is_executable = True)
    return [
        DefaultInfo(
            executable = executable,
        )
    ]
custom_test = rule(
    implementation = _impl,
    test = True,
)
EOF

    cat <<EOF > BUILD
load(":rules.bzl", "custom_test")

custom_test(name = "foo_test")
EOF
    if bazel coverage //:foo_test > $TEST_log; then
      fail "Coverage run succeeded but should have failed."
    fi
}


function test_starlark_rule_with_custom_lcov_merger() {
    add_rules_shell "MODULE.bazel"
    cat <<EOF > lcov_merger.sh
for var in "\$@"
do
    if [[ "\$var" == "--output_file="* ]]; then
        path="\${var##--output_file=}"
        mkdir -p "\$(dirname \$path)"
        echo lcov_merger_called >> \$path
        exit 0
    fi
done
EOF
chmod +x lcov_merger.sh

    cat <<EOF > rules.bzl
def _impl(ctx):
    output = ctx.actions.declare_file(ctx.attr.name)
    ctx.actions.write(output, "", is_executable = True)
    return [DefaultInfo(executable=output)]

custom_test = rule(
    implementation = _impl,
    test = True,
    attrs = {
        "_lcov_merger": attr.label(default = ":lcov_merger", cfg = "exec"),
    },
)
EOF

    cat <<EOF > BUILD
load(":rules.bzl", "custom_test")
load("@rules_shell//shell:sh_binary.bzl", "sh_binary")

sh_binary(
    name = "lcov_merger",
    srcs = ["lcov_merger.sh"],
)
custom_test(name = "foo_test")
EOF

    bazel coverage --test_output=all //:foo_test --combined_report=lcov > $TEST_log \
        || fail "Coverage run failed but should have succeeded."

    local coverage_file_path="$( get_coverage_file_path_from_test_log )"
    cat $coverage_file_path
    grep "lcov_merger_called"  "$coverage_file_path" \
        || fail "Coverage report did not contain evidence of custom lcov_merger."
}

function test_starlark_rule_with_configuration_field_lcov_merger_coverage_enabled() {
    add_rules_shell "MODULE.bazel"
    cat <<EOF > lcov_merger.sh
for var in "\$@"
do
    if [[ "\$var" == "--output_file="* ]]; then
        path="\${var##--output_file=}"
        mkdir -p "\$(dirname \$path)"
        echo lcov_merger_called >> \$path
        exit 0
    fi
done
EOF
chmod +x lcov_merger.sh

    cat <<EOF > rules.bzl
def _impl(ctx):
    output = ctx.actions.declare_file(ctx.attr.name)
    ctx.actions.write(output, "", is_executable = True)
    return [DefaultInfo(executable=output)]

custom_test = rule(
    implementation = _impl,
    test = True,
    attrs = {
        "_lcov_merger": attr.label(
            default = configuration_field(fragment = "coverage", name = "output_generator"),
            cfg = "exec"
        ),
    },
    fragments = ["coverage"],
)
EOF

    cat <<EOF > BUILD
load(":rules.bzl", "custom_test")
load("@rules_shell//shell:sh_binary.bzl", "sh_binary")

sh_binary(
    name = "lcov_merger",
    srcs = ["lcov_merger.sh"],
)
custom_test(name = "foo_test")
EOF

    bazel coverage --test_output=all //:foo_test --combined_report=lcov --coverage_output_generator=//:lcov_merger > $TEST_log \
        || fail "Coverage run failed but should have succeeded."

    local coverage_file_path="$( get_coverage_file_path_from_test_log )"
    cat $coverage_file_path
    grep "lcov_merger_called"  "$coverage_file_path" \
        || fail "Coverage report did not contain evidence of custom lcov_merger."
}

function test_starlark_rule_with_configuration_field_lcov_merger_coverage_disabled() {

    cat <<EOF > rules.bzl
def _impl(ctx):
    if ctx.attr._lcov_merger:
        fail("Expected _lcov_merger to be None if coverage is not collected")
    output = ctx.actions.declare_file(ctx.attr.name)
    ctx.actions.write(output, "", is_executable = True)
    return [DefaultInfo(executable=output)]

custom_test = rule(
    implementation = _impl,
    test = True,
    attrs = {
        "_lcov_merger": attr.label(
            default = configuration_field(fragment = "coverage", name = "output_generator"),
            cfg = "exec"
        ),
    },
    fragments = ["coverage"],
)
EOF

    cat <<EOF > BUILD
load(":rules.bzl", "custom_test")

custom_test(name = "foo_test")
EOF

    bazel test --test_output=all //:foo_test > $TEST_log \
        || fail "Test run failed but should have succeeded."
}

function test_starlark_rule_default_baseline_coverage() {
  mkdir -p test
  cat <<'EOF' > test/rules.bzl
_COMMON_ATTRS = {
    "srcs": attr.label_list(
        allow_files = True,
    ),
    "deps": attr.label_list(),
    "_lcov_merger": attr.label(
        default = configuration_field(fragment = "coverage", name = "output_generator"),
        cfg = "exec",
    ),
}

def _my_library_impl(ctx):
    providers = []

    transitive_files = [dep[DefaultInfo].files for dep in (ctx.attr.deps + ctx.attr.srcs)]
    providers.append(
        DefaultInfo(
            files = depset(transitive = transitive_files),
        )
    )

    providers.append(
        coverage_common.instrumented_files_info(
            ctx = ctx,
            source_attributes = ["srcs"],
            dependency_attributes = ["deps"],
        )
    )

    return providers

my_library = rule(
    implementation = _my_library_impl,
    attrs = _COMMON_ATTRS,
)

def _my_test_impl(ctx):
    providers = []

    out = ctx.actions.declare_file(ctx.label.name)
    all_files = depset(transitive = [dep[DefaultInfo].files for dep in (ctx.attr.deps + ctx.attr.srcs)])
    script_content = "#!/bin/bash\n" + "\n".join([
        """cat <<E_O_F >> "$COVERAGE_DIR/my_test.dat"
SF:{}
DA:1,1
DA:2,0
LF:2
LH:1
end_of_record
E_O_F
""".format(f.path)
        for f in all_files.to_list()
    ])
    ctx.actions.write(out, script_content, is_executable = True)
    providers.append(DefaultInfo(executable = out))

    providers.append(
        coverage_common.instrumented_files_info(
            ctx = ctx,
            source_attributes = ["srcs"],
            dependency_attributes = ["deps"],
        )
    )

    return providers

my_test = rule(
    implementation = _my_test_impl,
    test = True,
    attrs = _COMMON_ATTRS,
)
EOF

  cat <<'EOF' > test/BUILD
load(":rules.bzl", "my_library", "my_test")

my_library(
    name = "untested_lib",
    srcs = [
        "untested_1.txt",
        "untested_2.txt",
    ],
)

my_library(
    name = "covered_lib",
    srcs = [
        "covered_1.txt",
        "covered_2.txt",
    ],
)

my_library(
    name = "all_libs",
    deps = [
        ":untested_lib",
        ":covered_lib",
    ],
)

my_library(
    name = "tested_libs",
    deps = [
        ":covered_lib",
    ],
)

my_test(
    name = "my_test",
    srcs = [
        "test_1.txt",
        "test_2.txt",
    ],
    deps = [":tested_libs"],
)
EOF
    touch test/{untested_1.txt,untested_2.txt,covered_1.txt,covered_2.txt,test_1.txt,test_2.txt}

    bazel coverage //test:my_test //test:all_libs --combined_report=lcov &> $TEST_log \
        || fail "Coverage run failed but should have succeeded."
    local expected_coverage="SF:test/covered_1.txt
FNF:0
FNH:0
DA:1,1
DA:2,0
LH:1
LF:2
end_of_record
SF:test/covered_2.txt
FNF:0
FNH:0
DA:1,1
DA:2,0
LH:1
LF:2
end_of_record
SF:test/untested_1.txt
FNF:0
FNH:0
LH:0
LF:0
end_of_record
SF:test/untested_2.txt
FNF:0
FNH:0
LH:0
LF:0
end_of_record"

    assert_coverage_result "$expected_coverage" bazel-out/_coverage/_coverage_report.dat
}

run_suite "test tests"
