#!/usr/bin/env bash

# parse bazel go test case index log
# param $1 file path
function parse_bazel_go_test_index_log() {
    indexReg="((===|---) (RUN|PASS|FAIL))|-- Test timed out at|(====================( Test output for //|=+))"
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

function parse_bazel_go_test_new_flaky_cases() {
    local logPath="$1"
    local resultFile="$2"

    echo '{}' >"$resultFile"
    [ -f "bazel-go-test-index.log" ] || parse_bazel_go_test_index_log "$logPath"

    # extract bazel flaky summary info
    grep -E 'FLAKY:|^\s*/.*/shard_[0-9]+_of_[0-9]+/test_attempts/attempt_[0-9]+.log\b' "$logPath" >bazel-flaky-summaries.log

    # extract bazel flaky targets
    local indexes=($(grep -n FLAKY bazel-flaky-summaries.log | grep -Eo "^[0-9]+"))
    local p=0
    while [ $p -lt "${#indexes[@]}" ]; do
        local target
        local targetShardOutputLineNum

        ###### extract target name ###############

        ###### extract shard flag in target ##########
        local e
        if [ $((p + 1)) -eq "${#indexes[@]}" ]; then
            e='$'
        else
            e=$((${indexes[${p+1}]} - 1))
        fi
        local ts="${indexes[${p}]}"
        local s=$((ts + 1))
        target=$(sed -n "${ts},${ts}p" bazel-flaky-summaries.log | grep -Eo "\b//[-_:/a-zA-Z0-9]+\b")

        ###### find the new flaky cases #######
        for targetShardFlag in $(sed -n "${s},${e}p" bazel-flaky-summaries.log | grep -Eo "shard_[0-9]+_of_[0-9]+"); do
            targetShardOutputLineNum=$(grep -E "^[0-9]+:=+ Test output for $target \(${targetShardFlag//_/ }\):" bazel-go-test-index.log | grep -Eo "^[0-9]+")
            local newFlakyCases=($(
                sed -nE "/^${targetShardOutputLineNum}:/,/^[0-9]+:={80}/p" bazel-go-test-index.log |
                    grep -E "=== RUN|--- PASS" |
                    grep -Eo "\bTest\w+" | sort | uniq -c |
                    grep "^\s*1\b" |
                    grep -Eo "\bTest\w+"
            ))

            ##### add into result json file.
            local caseJqArray
            caseJqArray=$(printf ',"%s"' "${newFlakyCases[@]}")
            caseJqArray=${caseJqArray:1}
            # WIP
            jq ".\"$target\" += {\"new\": [${caseJqArray}]}" "$resultFile" >"$resultFile".new
            mv "$resultFile".new "$resultFile"
        done

        p=$((p + 1))
    done

    exit 0
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
    parse_bazel_go_test_new_flaky_cases "$logPath" bazel-go-test-flaky-cases.json
}

main "$@"
