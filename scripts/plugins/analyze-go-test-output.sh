#!/usr/bin/env bash

set -euo pipefail

DEFAULT_GO_TEST_INDEX_FILE="go-test-index.log"
DEFAULT_GO_TEST_PROBLEM_CASES_JSONFILE="go-test-problem-cases.json"
GO_TEST_PACKAGE_OUTPUT_FILE_PREFIX="go-test-package-output"

function parse_go_test_index_log() {
    local logPath="$1"
    local indexReg='^=== RUN[[:space:]]+|^--- (PASS|FAIL|SKIP):|^panic: test timed out after|^(ok|FAIL|\?)[[:space:]]+[^[:space:]]+([[:space:]]|$)'

    grep -nE "$indexReg" "$logPath" >"$DEFAULT_GO_TEST_INDEX_FILE" || true
}

function parse_go_test_package_output_log() {
    local logPath="$1"

    rm -f "$GO_TEST_PACKAGE_OUTPUT_FILE_PREFIX"-*.log
    awk -v prefix="$GO_TEST_PACKAGE_OUTPUT_FILE_PREFIX" '
        function sanitize(value) {
            gsub(/[^[:alnum:]._-]+/, "_", value)
            return value
        }
        function reset_state() {
            start = 0
            line_count = 0
            current_pkg = ""
            package_failed = 0
            package_timed_out = 0
            delete lines
        }
        function flush_package() {
            local_name = sanitize(current_pkg)
            if (start == 0 || current_pkg == "") {
                reset_state()
                return
            }

            suffix = ""
            if (package_timed_out) {
                suffix = suffix ".timeout"
            } else if (package_failed) {
                suffix = suffix ".fail"
            }

            output = sprintf("%s-L%d-L%d-%s%s.log", prefix, start, NR, local_name, suffix)
            for (i = 1; i <= line_count; i++) {
                print lines[i] > output
            }
            close(output)
            reset_state()
        }
        {
            if (start == 0) {
                start = NR
            }
            lines[++line_count] = $0
        }
        /^panic: test timed out after/ {
            package_timed_out = 1
        }
        /^--- FAIL:/ {
            package_failed = 1
        }
        /^(ok|FAIL|\?)[[:space:]]+[^[:space:]]+([[:space:]]|$)/ {
            if ($1 != "?") {
                current_pkg = $2
                flush_package()
            } else {
                reset_state()
            }
        }
        END {
            reset_state()
        }
    ' "$logPath"
}

function emit_go_test_problem_case_records() {
    local logPath="$1"

    awk '
        function reset_state() {
            delete started
            delete finished
            delete passed
            delete failed
            delete order
            started_count = 0
            timed_out = 0
        }
        function emit_record(kind, pkg, test_name, value) {
            if (pkg != "" && test_name != "") {
                print kind "\t" pkg "\t" test_name "\t" value
            }
        }
        function flush_package(pkg,    i, test_name) {
            if (pkg == "") {
                reset_state()
                return
            }

            if (timed_out) {
                for (i = 1; i <= started_count; i++) {
                    test_name = order[i]
                    if (test_name in passed) {
                        emit_record("long", pkg, test_name, passed[test_name])
                    }
                    if (!(test_name in finished)) {
                        emit_record("timeout", pkg, test_name, -1)
                    }
                }
            }

            for (test_name in failed) {
                emit_record("fail", pkg, test_name, failed[test_name])
            }

            reset_state()
        }
        BEGIN {
            reset_state()
        }
        /^=== RUN[[:space:]]+/ {
            test_name = $0
            sub(/^=== RUN[[:space:]]+/, "", test_name)
            if (!(test_name in started)) {
                order[++started_count] = test_name
            }
            started[test_name] = 1
            next
        }
        /^--- (PASS|FAIL|SKIP):[[:space:]]+/ {
            line = $0
            status = line
            sub(/^--- /, "", status)
            sub(/:.*/, "", status)

            test_name = line
            sub(/^--- (PASS|FAIL|SKIP):[[:space:]]+/, "", test_name)
            sub(/[[:space:]]+\(.*/, "", test_name)

            duration = -1
            if (line ~ /\([0-9.]+s\)/) {
                duration = line
                sub(/^.*\(/, "", duration)
                sub(/s\).*$/, "", duration)
            }

            finished[test_name] = 1
            if (status == "PASS") {
                passed[test_name] = duration
            } else if (status == "FAIL") {
                failed[test_name] = duration
            }
            next
        }
        /^panic: test timed out after/ {
            timed_out = 1
            next
        }
        /^(ok|FAIL|\?)[[:space:]]+[^[:space:]]+([[:space:]]|$)/ {
            if ($1 == "?") {
                reset_state()
            } else {
                flush_package($2)
            }
            next
        }
        END {
            reset_state()
        }
    ' "$logPath"
}

function update_go_test_problem_cases_json() {
    local resultFile="$1"
    local recordsFile="$2"

    echo "{}" >"$resultFile"

    while IFS=$'\t' read -r kind pkg test_name value; do
        [ -n "$kind" ] || continue

        case "$kind" in
            fail)
                jq \
                    --arg pkg "$pkg" \
                    --arg test_name "$test_name" \
                    '(.[$pkg] //= {}) |
                    .[$pkg].new_flaky |= ((. // []) + [{"name": $test_name, "reason": "fail"}] | unique)' \
                    "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
                ;;
            timeout)
                jq \
                    --arg pkg "$pkg" \
                    --arg test_name "$test_name" \
                    '(.[$pkg] //= {}) |
                    .[$pkg].new_flaky |= ((. // []) + [{"name": $test_name, "reason": "timeout"}] | unique) |
                    (.[$pkg].long_time //= {}) |
                    .[$pkg].long_time[$test_name] = -1' \
                    "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
                ;;
            long)
                jq \
                    --arg pkg "$pkg" \
                    --arg test_name "$test_name" \
                    --argjson duration "$value" \
                    '(.[$pkg] //= {}) |
                    (.[$pkg].long_time //= {}) |
                    .[$pkg].long_time[$test_name] = (if .[$pkg].long_time[$test_name] != null and .[$pkg].long_time[$test_name] >= 0 then .[$pkg].long_time[$test_name] else $duration end)' \
                    "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
                ;;
        esac
    done <"$recordsFile"
}

function main() {
    local logPath="$1"
    local recordsFile="go-test-problem-cases.records"

    parse_go_test_index_log "$logPath"
    parse_go_test_package_output_log "$logPath"

    emit_go_test_problem_case_records "$logPath" >"$recordsFile"
    update_go_test_problem_cases_json "$DEFAULT_GO_TEST_PROBLEM_CASES_JSONFILE" "$recordsFile"

    echo "Output files:"
    ls "$DEFAULT_GO_TEST_PROBLEM_CASES_JSONFILE" \
        "$DEFAULT_GO_TEST_INDEX_FILE" \
        "$GO_TEST_PACKAGE_OUTPUT_FILE_PREFIX"-*.log || true

    rm -f "$recordsFile"
}

main "$@"
