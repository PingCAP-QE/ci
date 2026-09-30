#!/usr/bin/env bash

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cd "$tmpdir"

bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
    "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/sample.log" >/dev/null 2>&1

test -f bazel-target-output-L11-14.timeout.log
test ! -e bazel-target-output-L1-4.fatal.log

# Helper: assert a test name is NOT in new_flaky for a given target
assert_not_flaky() {
    local fixture="$1" target="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
         "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/$fixture" >/dev/null 2>&1 && \
     jq -e --arg t "$test_name" \
        '.["'"$target"'"].new_flaky // [] | map(.name) | index($t) | not' \
        bazel-go-test-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should NOT be in new_flaky for $fixture"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name not in new_flaky ($fixture)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test name IS in new_flaky for a given target
assert_flaky() {
    local fixture="$1" target="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
         "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/$fixture" >/dev/null 2>&1 && \
     jq -e --arg t "$test_name" \
        '.["'"$target"'"].new_flaky // [] | map(.name) | index($t)' \
        bazel-go-test-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should be in new_flaky for $fixture"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name in new_flaky ($fixture)"
    rm -rf "$tmpdir2"
}

# Helper: assert the reason recorded for a test name in new_flaky for a target
assert_reason() {
    local fixture="$1" target="$2" test_name="$3" expected="$4"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
         "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/$fixture" >/dev/null 2>&1 && \
     jq -e --arg t "$test_name" --arg r "$expected" \
        '[.["'"$target"'"].new_flaky // [] | .[] | select(.name == $t) | .reason] | index($r)' \
        bazel-go-test-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name reason is not '$expected' for $fixture"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name reason == $expected ($fixture)"
    rm -rf "$tmpdir2"
}

# Helper: assert the script prints no sed error. A case appearing in several
# shards/attempts used to yield multiple line numbers and break the sed range,
# printing "unterminated address regex".
assert_no_sed_error() {
    local fixture="$1"
    local tmpdir2="$(mktemp -d)"
    local out
    out=$(cd "$tmpdir2" && bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
        "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/$fixture" 2>&1)
    rm -rf "$tmpdir2"
    if echo "$out" | grep -qiE "unterminated (address|regular expression)"; then
        echo "FAIL: script printed a sed error for $fixture"
        return 1
    fi
    echo "PASS: no sed error ($fixture)"
}

echo "--- TDD tests: flaky detection ---"

failures=0

# Test 1: SKIP-only test should NOT be flagged as flaky
assert_not_flaky "skip_only.log" "//pkg:skip_case" "TestSplitRangeForTable" || failures=$((failures + 1))

# Test 2: FAIL in shard1 + PASS in shard2 should be flagged as flaky
assert_flaky "flaky_two_shards.log" "//pkg:flaky_case" "TestFlaky" || failures=$((failures + 1))

# Test 3: Mixed — SKIP test not flagged, flaky test IS flagged
assert_not_flaky "skip_and_flaky.log" "//pkg:mixed_case" "TestSkipOnly" || failures=$((failures + 1))
assert_flaky "skip_and_flaky.log" "//pkg:mixed_case" "TestFlaky" || failures=$((failures + 1))

# Test 4: a failing case that shows up in multiple shards and whose race report is
# printed after "--- FAIL" must be flagged with reason "race" (regression for the
# multiline sed range and the truncated race-detection window).
assert_flaky "race_multi_shard.log" "//pkg:race_case" "TestRaceFlaky" || failures=$((failures + 1))
assert_reason "race_multi_shard.log" "//pkg:race_case" "TestRaceFlaky" "race" || failures=$((failures + 1))
assert_no_sed_error "race_multi_shard.log" || failures=$((failures + 1))

echo ""
if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures assertion(s) failed"
    exit 1
else
    echo "All TDD tests passed."
fi
