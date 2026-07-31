#!/usr/bin/env bash

set -euo pipefail

DEFAULT_NEXTEST_INDEX_FILE="nextest-index.log"
DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE="nextest-problem-cases.json"
NEXTEST_SLOW_TIME_THRESHOLD="${NEXTEST_SLOW_TIME_THRESHOLD:-60}"
MAX_PANIC_LENGTH="${MAX_PANIC_LENGTH:-200}"

# Extract significant lines (status, summary, panic markers) with line numbers
# param $1 file path
function parse_nextest_index_log() {
    local logPath="$1"
    local indexReg='^[[:space:]]+(TRY [0-9]+ (FAIL|PASS|TIMEOUT)|FAIL|PASS|SKIP|SLOW|TIMEOUT|FLAKY [0-9]+/[0-9]+)([[:space:]]+\[|$)|^[[:space:]]+Summary \[|panicked at|race detected during execution'

    grep -nE "$indexReg" "$logPath" >"$DEFAULT_NEXTEST_INDEX_FILE" || true
}

# Identify flaky tests from FLAKY summary lines and attempt-tracked retry success
# param $1 file path
# param $2 result json file
function parse_nextest_flaky_cases() {
    local logPath="$1"
    local resultFile="$2"
    local recordsFile="${resultFile}.flaky.records"

    [ -f "$DEFAULT_NEXTEST_INDEX_FILE" ] || parse_nextest_index_log "$logPath"

    awk '
        function emit(binary, test, reason) {
            print binary "\t" test "\t" reason
        }
        {
            sub(/^[0-9]+:/, "")
            if (match($0, /^[[:space:]]+(TRY [0-9]+ (FAIL|PASS|TIMEOUT)|FAIL|PASS|SKIP|SLOW|TIMEOUT|FLAKY [0-9]+\/[0-9]+)([[:space:]]+\[|$)/)) {
                line = $0
                sub(/^[[:space:]]+/, "", line)
                split(line, f, /[[:space:]]+/)
                if (f[1] == "TRY") {
                    attempt = f[2] + 0
                    status = f[3]
                    has_try = 1
                } else {
                    status = f[1]
                    attempt = 1
                    has_try = 0
                }
                binary = $(NF-1)
                test = $NF
                key = binary SUBSEP test
                if (has_try) try_keys[key] = 1
                if (status == "TIMEOUT") timeout_keys[key] = 1
                if (status == "FAIL" || status == "TIMEOUT") {
                    if (has_try || !try_keys[key]) {
                        if (first_fail[key] == 0 || attempt < first_fail[key]) first_fail[key] = attempt
                    }
                }
                if (status == "PASS") {
                    if (has_try || !try_keys[key]) {
                        if (min_pass[key] == 0 || attempt < min_pass[key]) min_pass[key] = attempt
                    }
                }
                if (f[1] == "FLAKY") {
                    reason = (timeout_keys[key] ? "timeout" : (race_keys[key] ? "race" : "flaky"))
                    emit(binary, test, reason)
                }
                current_key = key
                next
            }
            if (/race detected/) {
                if (current_key != "") race_keys[current_key] = 1
            }
        }
        END {
            for (key in try_keys) {
                split(key, parts, SUBSEP)
                if (first_fail[key] > 0 && min_pass[key] > 0 && min_pass[key] > first_fail[key]) {
                    reason = (timeout_keys[key] ? "timeout" : (race_keys[key] ? "race" : "flaky"))
                    emit(parts[1], parts[2], reason)
                }
            }
        }
    ' "$logPath" >"$recordsFile"

    sort -u "$recordsFile" |
        while IFS=$'\t' read -r binary test_name reason; do
            [ -n "$binary" ] || continue
            jq --arg binary "$binary" \
               --arg test_name "$test_name" \
               --arg reason "$reason" \
               '(.[$binary] //= {}) |
                .[$binary].new_flaky |= ((. // []) + [{"name": $test_name, "reason": $reason}] | unique)' \
               "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
        done

    rm -f "$recordsFile"
}

# Extract test durations; flag tests marked SLOW, timed out, or exceeding threshold
# param $1 file path
# param $2 result json file
function parse_nextest_long_cases() {
    local logPath="$1"
    local resultFile="$2"
    local recordsFile="${resultFile}.long.records"

    [ -f "$DEFAULT_NEXTEST_INDEX_FILE" ] || parse_nextest_index_log "$logPath"

    awk -v threshold="$NEXTEST_SLOW_TIME_THRESHOLD" '
        {
            sub(/^[0-9]+:/, "")
            if (match($0, /^[[:space:]]+(TRY [0-9]+ (FAIL|PASS|TIMEOUT)|FAIL|PASS|SKIP|SLOW|TIMEOUT)([[:space:]]+\[|$)/)) {
                line = $0
                sub(/^[[:space:]]+/, "", line)
                split(line, f, /[[:space:]]+/)
                if (f[1] == "TRY") {
                    status = f[3]
                } else {
                    status = f[1]
                }
                binary = $(NF-1)
                test = $NF
                key = binary SUBSEP test
                duration = -1
                if (match(line, /\[[[:space:]]*[0-9.]+s\]/)) {
                    dur_str = substr(line, RSTART + 1, RLENGTH - 2)
                    gsub(/[[:space:]]s/, "", dur_str)
                    duration = dur_str + 0
                }
                if (duration >= 0 && duration > max_duration[key]) max_duration[key] = duration
                if (status == "TIMEOUT") timeout_keys[key] = 1
                if (status == "SLOW") slow_keys[key] = 1
                if (status == "SKIP") skip_keys[key] = 1
            }
        }
        END {
            for (key in max_duration) {
                split(key, parts, SUBSEP)
                if (skip_keys[key]) continue
                if (timeout_keys[key]) {
                    print parts[1] "\t" parts[2] "\t-1"
                } else if (slow_keys[key] || max_duration[key] >= threshold) {
                    print parts[1] "\t" parts[2] "\t" max_duration[key]
                }
            }
        }
    ' "$logPath" >"$recordsFile"

    sort -u "$recordsFile" |
        while IFS=$'\t' read -r binary test_name duration; do
            [ -n "$binary" ] || continue
            jq --arg binary "$binary" \
               --arg test_name "$test_name" \
               --argjson duration "$duration" \
               '(.[$binary] //= {}) |
                (.[$binary].long_time //= {}) |
                .[$binary].long_time[$test_name] = (if .[$binary].long_time[$test_name] != null and .[$binary].long_time[$test_name] >= 0 then .[$binary].long_time[$test_name] else $duration end)' \
               "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
        done

    rm -f "$recordsFile"
}

# Identify binaries with panics: tests that failed on all retries with `panicked at`
# param $1 file path
# param $2 result json file
function parse_nextest_crash_cases() {
    local logPath="$1"
    local resultFile="$2"
    local recordsFile="${resultFile}.crash.records"

    [ -f "$DEFAULT_NEXTEST_INDEX_FILE" ] || parse_nextest_index_log "$logPath"

    awk -v max_panic_len="$MAX_PANIC_LENGTH" '
        {
            sub(/^[0-9]+:/, "")
            if (match($0, /^[[:space:]]+(TRY [0-9]+ (FAIL|PASS|TIMEOUT)|FAIL|PASS|SKIP|SLOW|TIMEOUT)([[:space:]]+\[|$)/)) {
                line = $0
                sub(/^[[:space:]]+/, "", line)
                split(line, f, /[[:space:]]+/)
                if (f[1] == "TRY") {
                    attempt = f[2] + 0
                    status = f[3]
                    has_try = 1
                } else {
                    status = f[1]
                    attempt = 1
                    has_try = 0
                }
                binary = $(NF-1)
                test = $NF
                key = binary SUBSEP test
                if (has_try) try_keys[key] = 1
                if (status == "PASS" && (has_try || !try_keys[key])) passed_keys[key] = 1
                if ((status == "FAIL" || status == "TIMEOUT") && (has_try || !try_keys[key])) {
                    failed_keys[key] = 1
                    if (attempt > max_attempt[key]) max_attempt[key] = attempt
                }
                if (!(key in seen_keys)) {
                    seen_keys[key] = 1
                    key_order[++key_count] = key
                }
                current_key = key
                next
            }
            if (/panicked at/) {
                if (current_key != "" && !(current_key in panic_seen)) {
                    line = $0
                    sub(/^[0-9]+:/, "")
                    sub(/^[[:space:]]+/, "", line)
                    panic_msg[current_key] = substr(line, 1, max_panic_len)
                    panic_seen[current_key] = 1
                }
            }
        }
        END {
            for (i = 1; i <= key_count; i++) {
                key = key_order[i]
                if (!(key in failed_keys)) continue
                if (key in passed_keys) continue
                if (!(key in panic_seen)) continue
                split(key, parts, SUBSEP)
                print parts[1] "\t" parts[2] "\t" max_attempt[key] "\t" panic_msg[key]
            }
        }
    ' "$logPath" >"$recordsFile"

    declare -A crash_attempt
    declare -A crash_panic
    declare -A crash_cases
    local -a crash_order=()

    while IFS=$'\t' read -r binary test_name attempt panic; do
        [ -n "$binary" ] || continue
        if [ -z "${crash_attempt[$binary]+x}" ]; then
            crash_order+=("$binary")
            crash_attempt[$binary]=$attempt
            crash_panic[$binary]="$panic"
            crash_cases[$binary]="$test_name"
        else
            if [ "$attempt" -gt "${crash_attempt[$binary]}" ]; then
                crash_attempt[$binary]=$attempt
            fi
            crash_cases[$binary]="${crash_cases[$binary]}\n$test_name"
        fi
    done <"$recordsFile"

    local binary
    for binary in "${crash_order[@]}"; do
        local casesJson="[]"
        if [ -n "${crash_cases[$binary]}" ]; then
            casesJson=$(printf '%b\n' "${crash_cases[$binary]}" | jq -R . | jq -s -c .)
        fi
        jq --arg binary "$binary" \
           --argjson attempt "${crash_attempt[$binary]}" \
           --argjson cases "$casesJson" \
           --arg panic "${crash_panic[$binary]}" \
           '(.[$binary] //= {}) |
            .[$binary].crash += [{"attempt": $attempt, "cases": $cases, "panic": $panic}]' \
           "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
    done

    rm -f "$recordsFile"
}

# param $1 file path or url
function main() {
    local logPath="$1"

    if [[ $logPath =~ https?://.* ]]; then
        echo "Parse from remote url: $logPath"
        wget -O nextest-output.log "$logPath"
        logPath="nextest-output.log"
    else
        echo "Parse from local file: $logPath"
    fi

    parse_nextest_index_log "$logPath"

    echo "{}" >"$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE"
    parse_nextest_flaky_cases "$logPath" "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE"
    parse_nextest_long_cases "$logPath" "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE"
    parse_nextest_crash_cases "$logPath" "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE"

    echo "Output files:"
    ls "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE" \
        "$DEFAULT_NEXTEST_INDEX_FILE" || true
}

main "$@"
