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

echo "--- TDD tests: flaky detection ---"

failures=0

# Test 1: SKIP-only test should NOT be flagged as flaky
assert_not_flaky "skip_only.log" "//pkg:skip_case" "TestSplitRangeForTable" || failures=$((failures + 1))

# Test 2: FAIL in shard1 + PASS in shard2 should be flagged as flaky
assert_flaky "flaky_two_shards.log" "//pkg:flaky_case" "TestFlaky" || failures=$((failures + 1))

# Test 3: Mixed — SKIP test not flagged, flaky test IS flagged
assert_not_flaky "skip_and_flaky.log" "//pkg:mixed_case" "TestSkipOnly" || failures=$((failures + 1))
assert_flaky "skip_and_flaky.log" "//pkg:mixed_case" "TestFlaky" || failures=$((failures + 1))

echo ""
if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures assertion(s) failed"
    exit 1
else
    echo "All TDD tests passed."
fi
