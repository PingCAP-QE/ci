#!/usr/bin/env bash

# parse bazel go test case index log
# param $1 file path
function parse_bazel_go_test_index_log() {
    indexReg="((===|---) (RUN|PASS|FAIL))|-- Test timed out at|FLAKY|(====================( Test output for //|=+))"
    local logPath="$1"
    grep --color -nE "$indexReg" "$logPath" >bazel-go-test-index.log
}

function parse_bazel_target_output_log() {
    local logPath="$1"

    local indexes=($(grep -nE "====================( Test output for //|=+)" ${logPath} | grep -oE "^[0-9]+"))
    local p=0
    while [ $((2 * p)) -lt "${#indexes[@]}" ]; do
        saveFlag="bazel-target-output-L${indexes[$((2 * p))]}-${indexes[$((2 * p + 1))]}"
        sed -n "${indexes[$((2 * p))]},${indexes[$((2 * p + 1))]}p" "${logPath}" >"$saveFlag.log"
        local append=""

        if grep -E "^-- Test timed out at" "$saveFlag.log" >/dev/null; then
            append="$append.timeout"
        elif grep -E "^--- FAIL" "$saveFlag.log" >/dev/null; then
            append="$append.fail"
        elif grep -E "(---|===) (PASS|FAIL|RUN)" "$saveFlag.log" | grep -oE "\bTest\w+\b" | sort | uniq -c | grep "^\s*1\b" >/dev/null; then
            append="$append.fatal"
        fi

        if [ "$append" != "" ]; then
            mv "$saveFlag.log" "$saveFlag${append}.log"
        fi
        p=$((p + 1))
    done
}

# param $1 file path or url
function main() {
    local logPath="$1"

    if [[ $logPath =~ https?://.* ]]; then
        echo "Parse from remote url: $logPath"
        wget -O bazel-output.log "$logPath"
        logPath="bazel-output.log"
    else
        echo "Parse from local file: $logPath"
    fi

    parse_bazel_go_test_index_log "$logPath"
    parse_bazel_target_output_log "$logPath"
}

main "$@"
