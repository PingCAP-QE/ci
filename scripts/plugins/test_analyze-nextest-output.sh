#!/usr/bin/env bash

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cd "$tmpdir"

FIXTURE_DIR="$repo_root/scripts/plugins/testdata/analyze-nextest-output"
ANALYZER="$repo_root/scripts/plugins/analyze-nextest-output.sh"

# Helper: assert a test name is in new_flaky for a given binary
assert_flaky() {
    local fixture="$1" binary="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" \
        '(.[$b].new_flaky // []) | map(.name) | index($t)' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should be in new_flaky for $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name in new_flaky ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test name is NOT in new_flaky for a given binary
assert_not_flaky() {
    local fixture="$1" binary="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" \
        '(.[$b].new_flaky // []) | map(.name) | index($t) | not' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should NOT be in new_flaky for $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name not in new_flaky ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert the reason of a flaky test entry
assert_flaky_reason() {
    local fixture="$1" binary="$2" test_name="$3" reason="$4"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" --arg r "$reason" \
        '(.[$b].new_flaky // []) | any(.name == $t and .reason == $r)' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should have reason '$reason' in $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name reason is '$reason' ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test name is in crash cases for a given binary
assert_crash() {
    local fixture="$1" binary="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" \
        '(.[$b].crash // []) | any(.cases | index($t))' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should be in crash for $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name in crash ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test name is NOT in crash cases for a given binary
assert_no_crash() {
    local fixture="$1" binary="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" \
        '(.[$b].crash // []) | any(.cases | index($t)) | not' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should NOT be in crash for $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name not in crash ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert the crash attempt count of a test in a given binary
assert_crash_attempt() {
    local fixture="$1" binary="$2" test_name="$3" attempt="$4"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" --argjson a "$attempt" \
        'any((.[$b].crash // [])[] | select(.cases | index($t)); .attempt == $a)' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: crash attempt for $test_name should be $attempt in $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: crash attempt for $test_name is $attempt ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test has a long_time entry with duration >= min (or == -1 when min < 0)
assert_long_time() {
    local fixture="$1" binary="$2" test_name="$3" min_duration="$4"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" --argjson min "$min_duration" \
        '(.[$b].long_time[$t]? != null) and (if $min < 0 then .[$b].long_time[$t] == -1 else .[$b].long_time[$t] >= $min end)' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should have long_time >= $min_duration in $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name long_time is $min_duration ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

# Helper: assert a test has no long_time entry
assert_no_long_time() {
    local fixture="$1" binary="$2" test_name="$3"
    local tmpdir2="$(mktemp -d)"
    (cd "$tmpdir2" && \
     bash "$ANALYZER" "$FIXTURE_DIR/$fixture" >/dev/null 2>&1 && \
     jq -e --arg b "$binary" --arg t "$test_name" \
        '(.[$b].long_time[$t]? == null)' \
        nextest-problem-cases.json >/dev/null) || \
        { echo "FAIL: $test_name should NOT have a long_time entry in $fixture ($binary)"; rm -rf "$tmpdir2"; return 1; }
    echo "PASS: $test_name no long_time ($fixture / $binary)"
    rm -rf "$tmpdir2"
}

failures=0

echo "--- TDD tests: output artifacts ---"
smoke_dir="$(mktemp -d)"
if (cd "$smoke_dir" && \
     bash "$ANALYZER" "$FIXTURE_DIR/sample.log" >/dev/null 2>&1 && \
     test -f nextest-index.log && \
     test -f nextest-problem-cases.json && \
     jq -e 'length > 0' nextest-problem-cases.json >/dev/null); then
    echo "PASS: output artifacts (nextest-index.log, nextest-problem-cases.json) produced"
else
    echo "FAIL: output artifacts missing or empty"
    failures=$((failures + 1))
fi
rm -rf "$smoke_dir"

echo "--- TDD tests: flaky detection ---"

# Smoke test: mixed pass/fail/flaky/crash
assert_flaky "sample.log" "fixture::basic" "test_flaky_mod_2" || failures=$((failures + 1))
assert_flaky_reason "sample.log" "fixture::basic" "test_flaky_mod_2" "flaky" || failures=$((failures + 1))
assert_not_flaky "sample.log" "fixture::panic" "test_flaky_mod_2" || failures=$((failures + 1))

# Flaky detection from FLAKY summary + retry success
assert_flaky "flaky_retry_pass.log" "fixture::basic" "test_flaky_mod_2" || failures=$((failures + 1))
assert_flaky_reason "flaky_retry_pass.log" "fixture::basic" "test_flaky_mod_2" "flaky" || failures=$((failures + 1))
assert_not_flaky "flaky_retry_pass.log" "fixture::basic" "test_pass" || failures=$((failures + 1))

# Timeout-then-pass is flaky with reason=timeout
assert_flaky "flaky_timeout.log" "fixture::basic" "test_flaky_timeout" || failures=$((failures + 1))
assert_flaky_reason "flaky_timeout.log" "fixture::basic" "test_flaky_timeout" "timeout" || failures=$((failures + 1))

# No false positive on all-pass
assert_not_flaky "all_pass.log" "fixture::basic" "test_pass_1" || failures=$((failures + 1))
assert_not_flaky "all_pass.log" "fixture::basic" "test_pass_2" || failures=$((failures + 1))

# No false positive on always-fail
assert_not_flaky "persistent_fail.log" "fixture::basic" "test_always_fail" || failures=$((failures + 1))

# No flaky when no retries configured
assert_not_flaky "no_flaky_no_crash.log" "fixture::basic" "test_fail_no_panic" || failures=$((failures + 1))

# Multi-binary isolation
assert_flaky "multiple_binaries.log" "crate_a::tests" "test_flaky_a" || failures=$((failures + 1))
assert_flaky "multiple_binaries.log" "crate_b::tests" "test_flaky_b" || failures=$((failures + 1))
assert_not_flaky "multiple_binaries.log" "crate_b::tests" "test_flaky_a" || failures=$((failures + 1))
assert_not_flaky "multiple_binaries.log" "crate_a::tests" "test_flaky_b" || failures=$((failures + 1))

echo "--- TDD tests: crash detection ---"

assert_crash "sample.log" "fixture::panic" "test_crash" || failures=$((failures + 1))
assert_crash_attempt "sample.log" "fixture::panic" "test_crash" 2 || failures=$((failures + 1))
assert_crash "persistent_fail.log" "fixture::basic" "test_always_fail" || failures=$((failures + 1))
assert_crash_attempt "persistent_fail.log" "fixture::basic" "test_always_fail" 3 || failures=$((failures + 1))

# Flaky test that eventually passed is NOT a crash
assert_no_crash "flaky_retry_pass.log" "fixture::basic" "test_flaky_mod_2" || failures=$((failures + 1))
assert_no_crash "flaky_timeout.log" "fixture::basic" "test_flaky_timeout" || failures=$((failures + 1))
# All-pass and no-retries plain failures without panic are NOT crashes
assert_no_crash "all_pass.log" "fixture::basic" "test_pass_1" || failures=$((failures + 1))
assert_no_crash "no_flaky_no_crash.log" "fixture::basic" "test_fail_no_panic" || failures=$((failures + 1))

echo "--- TDD tests: long time detection ---"

assert_long_time "slow_pass.log" "fixture::tests" "test_slow" 60 || failures=$((failures + 1))
assert_no_long_time "slow_pass.log" "fixture::tests" "test_fast" || failures=$((failures + 1))
assert_long_time "flaky_timeout.log" "fixture::basic" "test_flaky_timeout" -1 || failures=$((failures + 1))
assert_no_long_time "all_pass.log" "fixture::basic" "test_pass_1" || failures=$((failures + 1))
assert_no_long_time "no_flaky_no_crash.log" "fixture::basic" "test_fail_no_panic" || failures=$((failures + 1))

echo ""
if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures assertion(s) failed"
    exit 1
else
    echo "All TDD tests passed."
fi
