#!/usr/bin/env bash

set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
analyzer="$repo_root/scripts/plugins/analyze-nextest-output.sh"
testdata="$repo_root/scripts/plugins/testdata/analyze-nextest-output"

# Helper: run the analyzer against a fixture in a temp dir and evaluate a jq
# predicate against the produced nextest-problem-cases.json.
assert_json() {
    local fixture="$1" jq_expr="$2" desc="$3"
    local tmpdir2
    tmpdir2="$(mktemp -d)"
    if (cd "$tmpdir2" && \
        bash "$analyzer" "$testdata/$fixture" >/dev/null 2>&1 && \
        jq -e "$jq_expr" nextest-problem-cases.json >/dev/null); then
        echo "PASS: $desc ($fixture)"
        rm -rf "$tmpdir2"
        return 0
    fi
    echo "FAIL: $desc ($fixture)"
    rm -rf "$tmpdir2"
    return 1
}

# Helper: assert a test is in new_flaky for a binary, optionally with a reason
assert_flaky() {
    local fixture="$1" binary="$2" test_name="$3" reason="${4:-}"
    local expr
    if [ -n "$reason" ]; then
        expr='(.["'"$binary"'"].new_flaky // []) | any(.name == "'"$test_name"'" and .reason == "'"$reason"'")'
    else
        expr='(.["'"$binary"'"].new_flaky // []) | map(.name) | index("'"$test_name"'") != null'
    fi
    assert_json "$fixture" "$expr" "$test_name should be in new_flaky ($binary)" || return 1
}

# Helper: assert a test is NOT in new_flaky for a binary
assert_not_flaky() {
    local fixture="$1" binary="$2" test_name="$3"
    assert_json "$fixture" \
        '(.["'"$binary"'"].new_flaky // []) | map(.name) | index("'"$test_name"'") == null' \
        "$test_name should NOT be in new_flaky ($binary)" || return 1
}

# Helper: assert a test is in crash.cases for a binary
assert_crash() {
    local fixture="$1" binary="$2" test_name="$3"
    assert_json "$fixture" \
        '(.["'"$binary"'"].crash // []) | any(.cases | index("'"$test_name"'") != null)' \
        "$test_name should be in crash ($binary)" || return 1
}

# Helper: assert a test is NOT in crash.cases for a binary
assert_not_crash() {
    local fixture="$1" binary="$2" test_name="$3"
    assert_json "$fixture" \
        '(.["'"$binary"'"].crash // []) | any(.cases | index("'"$test_name"'") != null) | not' \
        "$test_name should NOT be in crash ($binary)" || return 1
}

# Helper: assert long_time value for a test (min_duration: minimum seconds, -1 for timeout)
assert_long_time() {
    local fixture="$1" binary="$2" test_name="$3" min_duration="$4"
    assert_json "$fixture" \
        '(.["'"$binary"'"].long_time // {})["'"$test_name"'"] >= '"$min_duration" \
        "$test_name long_time >= $min_duration ($binary)" || return 1
}

failures=0

echo "--- TDD tests: flaky detection ---"

# Test 1: all tests pass on first attempt -> nothing flagged
assert_json "all_pass.log" 'length == 0' "empty result for all-pass run" || failures=$((failures + 1))

# Test 2: FAIL on TRY 1 then PASS on TRY 2 -> flaky, reason flaky
assert_flaky "flaky_retry_pass.log" "demo" "tests::test_flaky" "flaky" || failures=$((failures + 1))

# Test 3: TMT on TRY 1 then PASS on TRY 2 -> flaky, reason timeout
assert_flaky "flaky_timeout.log" "demo" "tests::test_flaky_timeout" "timeout" || failures=$((failures + 1))

# Test 4: SLOW marked test that passes -> long_time recorded, not flaky, no crash
assert_json "slow_pass.log" \
    '(.["demo"].long_time // {})["tests::test_slow"] >= 0.9' \
    "test_slow long_time >= 0.9" || failures=$((failures + 1))
assert_not_flaky "slow_pass.log" "demo" "tests::test_slow" || failures=$((failures + 1))
assert_not_crash "slow_pass.log" "demo" "tests::test_slow" || failures=$((failures + 1))

# Test 5: persistent failure (FAIL on all retries, panic) -> crash, not flaky
assert_not_flaky "persistent_fail.log" "demo" "tests::test_always_fail" || failures=$((failures + 1))
assert_crash "persistent_fail.log" "demo" "tests::test_always_fail" || failures=$((failures + 1))
assert_json "persistent_fail.log" \
    '(.["demo"].crash[0].attempt | tonumber) == 3' \
    "crash attempt is 3 (all retries)" || failures=$((failures + 1))
assert_json "persistent_fail.log" \
    '("\(.["demo"].crash[0].panic)" | contains("always fails"))' \
    "crash panic message captured" || failures=$((failures + 1))

# Test 6: no retries configured -> FAIL is a crash with attempt 1, zero flaky
assert_not_flaky "no_retry_fail.log" "demo" "tests::test_always_fail" || failures=$((failures + 1))
assert_crash "no_retry_fail.log" "demo" "tests::test_always_fail" || failures=$((failures + 1))
assert_json "no_retry_fail.log" \
    '(.["demo"].crash[0].attempt | tonumber) == 1' \
    "crash attempt is 1 (no retries)" || failures=$((failures + 1))

# Test 7: flaky tests in two binaries are indexed by their own binary
assert_flaky "multiple_binaries.log" "demo" "tests::test_flaky" "flaky" || failures=$((failures + 1))
assert_flaky "multiple_binaries.log" "demo2" "tests::test_flaky_b" "flaky" || failures=$((failures + 1))
assert_not_flaky "multiple_binaries.log" "demo" "tests::test_flaky_b" || failures=$((failures + 1))
assert_not_flaky "multiple_binaries.log" "demo2" "tests::test_flaky" || failures=$((failures + 1))

# Test 8: sample mixed run — flaky, crash and long_time all detected
assert_flaky "sample.log" "demo" "tests::test_flaky" "flaky" || failures=$((failures + 1))
assert_crash "sample.log" "demo" "tests::test_always_fail" || failures=$((failures + 1))
assert_not_flaky "sample.log" "demo" "tests::test_ok" || failures=$((failures + 1))
assert_json "sample.log" \
    '(.["demo"].long_time // {})["tests::test_slow"] >= 0.9' \
    "test_slow long_time >= 0.9" || failures=$((failures + 1))

echo ""
if [ "$failures" -gt 0 ]; then
    echo "FAILED: $failures assertion(s) failed"
    exit 1
else
    echo "All TDD tests passed."
fi
