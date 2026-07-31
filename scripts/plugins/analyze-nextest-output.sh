#!/usr/bin/env bash

set -uo pipefail

DEFAULT_NEXTEST_INDEX_FILE="nextest-index.log"
DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE="nextest-problem-cases.json"

NEXTEST_SCAN_KEYS=()
declare -A NEXTEST_SCAN_ATTEMPTS
declare -A NEXTEST_SCAN_STATUSES
declare -A NEXTEST_SCAN_ELAPSED
declare -A NEXTEST_SCAN_SAW_PASS
declare -A NEXTEST_SCAN_TIMED_OUT
declare -A NEXTEST_SCAN_SLOW
declare -A NEXTEST_SCAN_FLAKY
declare -A NEXTEST_SCAN_PANIC

# nextest per-attempt status line, e.g.:
#   TRY 1 FAIL [   0.011s] (───) demo tests::test_flaky
#   TRY 2 PASS [   0.009s] (1/1) demo tests::test_flaky
#   TRY 1 TMT [   0.505s] (───) demo tests::test_flaky_timeout
#   TRY 1 TRMNTG [>  0.500s] (───) demo tests::test_flaky_timeout
#   SLOW [>  0.500s] (───) demo tests::test_slow
#   PASS [   0.004s] (1/1) demo tests::test_ok
NEXTEST_STATUS_REG='^[[:space:]]*(TRY[[:space:]]+([0-9]+)[[:space:]]+)?(FAIL|PASS|TMT|SLOW|TRMNTG)[[:space:]]+\[([^]]+)\][[:space:]]+(\([^)]*\)[[:space:]]+)?([^[:space:]]+)[[:space:]]+([^[:space:]]+)$'

# nextest post-run flaky marker line, e.g.:
#   FLAKY 2/3 [   0.009s] (1/1) demo tests::test_flaky
NEXTEST_FLAKY_REG='^[[:space:]]*FLAKY[[:space:]]+[0-9]+/[0-9]+[[:space:]]+\[[^]]+\][[:space:]]+(\([^)]*\)[[:space:]]+)?([^[:space:]]+)[[:space:]]+([^[:space:]]+)$'

NEXTEST_INDEX_REG='(TRY[[:space:]]+[0-9]+[[:space:]]+)?(FAIL|PASS|TMT|SLOW|TRMNTG)[[:space:]]+\[|^[[:space:]]*FLAKY[[:space:]]+|^[[:space:]]+Summary[[:space:]]+\[|panicked at'

# parse nextest log to index file: extract significant lines with line numbers
# param $1 file path
function parse_nextest_index_log() {
    local logPath="$1"
    grep --color -nE "$NEXTEST_INDEX_REG" "$logPath" >"$DEFAULT_NEXTEST_INDEX_FILE" || true
}

# extract the numeric duration from a nextest elapsed field like "   0.912s" or ">  0.500s"
function nextest_elapsed_seconds() {
    local elapsed="$1"
    printf '%s' "$elapsed" | sed -E 's/[^0-9.]//g; s/^\./0./'
}

# scan the raw log and emit per-test facts through the NEXTEST_SCAN_* globals
function scan_nextest_log() {
    local logPath="$1"

    declare -A attempts
    declare -A statuses
    declare -A elapsed
    declare -A saw_pass
    declare -A timed_out
    declare -A slow_marked
    declare -A flaky_marked
    declare -A panic_msg

    local current_key=""
    local prev_panic_line=""

    while IFS= read -r line; do
        if [[ "$line" =~ $NEXTEST_FLAKY_REG ]]; then
            local binary="${BASH_REMATCH[2]}"
            local testName="${BASH_REMATCH[3]}"
            flaky_marked["$binary|$testName"]=1
            current_key=""
            prev_panic_line=""
        elif [[ "$line" =~ $NEXTEST_STATUS_REG ]]; then
            local tryNum="${BASH_REMATCH[2]:-1}"
            local status="${BASH_REMATCH[3]}"
            local elapsedStr="${BASH_REMATCH[4]}"
            local binary="${BASH_REMATCH[6]}"
            local testName="${BASH_REMATCH[7]}"
            local key="$binary|$testName"

            attempts[$key]=$tryNum
            statuses[$key]=$status
            elapsed[$key]="$elapsedStr"

            if [ "$status" = "PASS" ]; then
                saw_pass[$key]=1
                current_key=""
            elif [ "$status" = "TMT" ]; then
                timed_out[$key]=1
                current_key="$key"
            elif [ "$status" = "SLOW" ]; then
                slow_marked[$key]=1
                current_key=""
            elif [ "$status" = "FAIL" ]; then
                current_key="$key"
            else
                current_key=""
            fi
            prev_panic_line=""
        elif [ -n "$current_key" ]; then
            if [[ "$line" == *"panicked at"* ]]; then
                prev_panic_line="${line#*panicked at }"
            elif [ -n "$prev_panic_line" ] && [ -z "${panic_msg[$current_key]:-}" ]; then
                local continuation
                continuation="$(printf '%s' "$line" | xargs)"
                local msg="$prev_panic_line"
                if [ -n "$continuation" ] && [[ "$continuation" != note:* ]]; then
                    msg="${msg} ${continuation}"
                fi
                panic_msg["$current_key"]="$(printf '%s' "$msg" | head -c 200)"
                prev_panic_line=""
            fi
        fi
    done <"$logPath"

    NEXTEST_SCAN_KEYS=()

    declare -A seen_keys
    local key
    for key in "${!statuses[@]}" "${!flaky_marked[@]}"; do
        if [ -n "${seen_keys[$key]:-}" ]; then
            continue
        fi
        seen_keys[$key]=1
        NEXTEST_SCAN_KEYS+=("$key")
        NEXTEST_SCAN_ATTEMPTS[$key]="${attempts[$key]:-1}"
        NEXTEST_SCAN_STATUSES[$key]="${statuses[$key]:-}"
        NEXTEST_SCAN_ELAPSED[$key]="${elapsed[$key]:-}"
        NEXTEST_SCAN_SAW_PASS[$key]="${saw_pass[$key]:-0}"
        NEXTEST_SCAN_TIMED_OUT[$key]="${timed_out[$key]:-0}"
        NEXTEST_SCAN_SLOW[$key]="${slow_marked[$key]:-0}"
        NEXTEST_SCAN_FLAKY[$key]="${flaky_marked[$key]:-0}"
        NEXTEST_SCAN_PANIC[$key]="${panic_msg[$key]:-}"
    done
}

function parse_nextest_problem_cases() {
    local logPath="$1"
    local resultFile="$2"

    echo "{}" >"$resultFile"

    scan_nextest_log "$logPath"

    declare -A crash_cases
    declare -A crash_attempt
    declare -A crash_panic

    local key
    for key in "${NEXTEST_SCAN_KEYS[@]}"; do
        local binary="${key%%|*}"
        local testName="${key##*|}"
        local lastStatus="${NEXTEST_SCAN_STATUSES[$key]}"
        local tryNum="${NEXTEST_SCAN_ATTEMPTS[$key]}"
        local elapsedStr="${NEXTEST_SCAN_ELAPSED[$key]}"

        # flaky: failed at least once then passed on a retry (FLAKY marker or attempt tracking)
        if [ "${NEXTEST_SCAN_FLAKY[$key]}" = "1" ] ||
            { [ "${NEXTEST_SCAN_SAW_PASS[$key]}" = "1" ] && { [ "$lastStatus" = "FAIL" ] || [ "$lastStatus" = "TMT" ]; }; }; then
            local reason="flaky"
            if [ "${NEXTEST_SCAN_TIMED_OUT[$key]}" = "1" ]; then
                reason="timeout"
            fi
            jq --arg binary "$binary" \
               --arg name "$testName" \
               --arg reason "$reason" \
               '(.[$binary] //= {}) | .[$binary].new_flaky |= ((. // []) + [{"name": $name, "reason": $reason}] | unique)' \
               "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
        fi

        # long_time: tests marked SLOW, duration of the last attempt (-1 for timeout)
        if [ "${NEXTEST_SCAN_SLOW[$key]}" = "1" ]; then
            local duration=-1
            if [ "$lastStatus" != "TMT" ] && [ "$lastStatus" != "TRMNTG" ]; then
                duration="$(nextest_elapsed_seconds "$elapsedStr")"
            fi
            jq --arg binary "$binary" \
               --arg name "$testName" \
               --argjson duration "$duration" \
               '(.[$binary] //= {}) | (.[$binary].long_time //= {}) | .[$binary].long_time[$name] = $duration' \
               "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
        fi

        # crash: tests that failed on all attempts (never passed), grouped by binary
        if [ "$lastStatus" = "FAIL" ] || [ "$lastStatus" = "TMT" ]; then
            if [ -n "${crash_cases[$binary]:-}" ]; then
                crash_cases[$binary]="${crash_cases[$binary]} $testName"
            else
                crash_cases[$binary]="$testName"
            fi
            if [ "$tryNum" -gt "${crash_attempt[$binary]:-0}" ]; then
                crash_attempt[$binary]="$tryNum"
            fi
            if [ -z "${crash_panic[$binary]:-}" ] && [ -n "${NEXTEST_SCAN_PANIC[$key]:-}" ]; then
                crash_panic[$binary]="${NEXTEST_SCAN_PANIC[$key]}"
            fi
        fi
    done

    local binary
    for binary in "${!crash_cases[@]}"; do
        local cases_json
        cases_json="$(
            for c in ${crash_cases[$binary]}; do
                printf '%s\n' "$c"
            done | jq -R . | jq -s -c .
        )"
        jq --arg binary "$binary" \
           --arg attempt "${crash_attempt[$binary]}" \
           --argjson cases "$cases_json" \
           --arg panic "${crash_panic[$binary]:-}" \
           '.[$binary] |= (. // {}) | .[$binary].crash += [{"attempt": $attempt, "cases": $cases, "panic": $panic}]' \
           "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
    done
}

# param $1 file path or url
function main() {
    local logPath="$1"

    if [[ $logPath =~ https?://.* ]]; then
        echo "Parse from remote url: $logPath"
        if command -v wget &>/dev/null; then
            wget -O nextest-output.log "$logPath"
        elif command -v curl &>/dev/null; then
            curl -o nextest-output.log -L "$logPath"
        else
            echo "Error: neither wget nor curl found." >&2
            exit 1
        fi
        logPath="nextest-output.log"
    else
        echo "Parse from local file: $logPath"
    fi

    parse_nextest_index_log "$logPath"
    parse_nextest_problem_cases "$logPath" "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE"

    echo "Output files:"
    ls "$DEFAULT_NEXTEST_PROBLEM_CASES_JSONFILE" \
        "$DEFAULT_NEXTEST_INDEX_FILE" || true
}

main "$@"
