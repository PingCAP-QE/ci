#!/usr/bin/env bash

DEFAULT_GO_TEST_INDEX_FILE="bazel-go-test-index.log"
DEFAILT_GO_TEST_PROBLEM_CASES_JSONFILE="bazel-go-test-problem-cases.json"
BAZEL_TARGET_OUTPUT_FILE_PREFIX="bazel-target-output"
BAZEL_FLAKY_SUMMARY_FILE="bazel-flaky-summaries.log"

# parse bazel go test case index log
# param $1 file path
function parse_bazel_go_test_index_log() {
    indexReg="((===|---) (RUN|PASS|FAIL))|-- Test timed out at|(====================( Test output for //|=+)|WARNING: DATA RACE|testing.go:[0-9]+: race detected during execution of test)"
    local logPath="$1"
    grep --color -nE "$indexReg" "$logPath" >"$DEFAULT_GO_TEST_INDEX_FILE"
}

function parse_bazel_target_output_log() {
    local logPath="$1"

    rm -rf "$BAZEL_TARGET_OUTPUT_FILE_PREFIX-*.log"
    local indexes=($(grep -nE "====================( Test output for //|=+)" ${logPath} | grep -oE "^[0-9]+"))
    local p=0
    while [ $((2 * p)) -lt "${#indexes[@]}" ]; do
        saveFlag="$BAZEL_TARGET_OUTPUT_FILE_PREFIX-L${indexes[$((2 * p))]}-${indexes[$((2 * p + 1))]}"
        sed -n "${indexes[$((2 * p))]},${indexes[$((2 * p + 1))]}p" "${logPath}" >"$saveFlag.log"
        local append=""

        if grep -E "^-- Test timed out at" "$saveFlag.log" >/dev/null; then
            append="$append.timeout"
        elif grep -E "^--- FAIL" "$saveFlag.log" >/dev/null; then
            append="$append.fail"
            if grep -E "testing.go:[0-9]+: race detected during execution of test" "$saveFlag.log" >/dev/null; then
                append="$append.race"
            fi

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

    [ -f "$DEFAULT_GO_TEST_INDEX_FILE" ] || parse_bazel_go_test_index_log "$logPath"

    # extract bazel flaky summary info
    grep -E 'FLAKY:|^\s*/.*/shard_[0-9]+_of_[0-9]+/test_attempts/attempt_[0-9]+.log\b' "$logPath" >"$BAZEL_FLAKY_SUMMARY_FILE"

    # extract bazel flaky targets
    local indexes=($(grep -n FLAKY "$BAZEL_FLAKY_SUMMARY_FILE" | grep -Eo "^[0-9]+"))
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
            targetShardOutputLineNum=$(grep -E "^[0-9]+:=+ Test output for $target \(${targetShardFlag//_/ }\):" "$DEFAULT_GO_TEST_INDEX_FILE" | grep -Eo "^[0-9]+")
            for n in $targetShardOutputLineNum; do
                local newFlakyCases=($(
                    sed -nE "/^${n}:/,/^[0-9]+:={80}/p" "$DEFAULT_GO_TEST_INDEX_FILE" |
                        grep -E "=== RUN|--- PASS" |
                        grep -Eo "\bTest\w+" | sort | uniq -c |
                        grep "^\s*1\b" |
                        grep -Eo "\bTest\w+"
                ))

                ##### add into result json file.
                if [ "${#newFlakyCases[@]}" -gt 0 ]; then
                    for c in "${newFlakyCases[@]}"; do
                        local caseRunStartLine=$(grep -E "=== RUN\s*${c}$" "$DEFAULT_GO_TEST_INDEX_FILE" | cut -d ":" -f 1)
                        local caseRunEndLine=$(grep -E "(--- FAIL):\s*${c}\b.*$" "$DEFAULT_GO_TEST_INDEX_FILE" | cut -d ":" -f 1)
                        local failReason="unknow"

                        # failed reason: race detected.
                        if sed -n "/^${caseRunStartLine}:/,/^${caseRunEndLine}:/p" "$DEFAULT_GO_TEST_INDEX_FILE" | grep "race detected during execution of test" >/dev/null; then
                            echo "race detected case: ${c}"
                            failReason="race"
                        else
                            echo "new flaky case: ${c}"
                        fi

                        jq ".\"$target\".new_flaky |= (. + [{\"name\":\"${c}\",\"reason\":\"${failReason}\"}] | unique)" \
                            "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
                    done
                fi
            done
        done

        p=$((p + 1))
    done
}

function parse_bazel_go_test_long_cases() {
    local logPath="$1"
    local resultFile="$2"

    [ -f "$DEFAULT_GO_TEST_INDEX_FILE" ] || parse_bazel_go_test_index_log "$logPath"

    for f in $BAZEL_TARGET_OUTPUT_FILE_PREFIX-*.timeout.log; do
        local target
        [[ -e "$f" ]] || break

        target=$(head -n1 "$f" | grep -Eo "//[-_:/a-zA-Z0-9]+\b")

        # add test cases with time cost value -1(means timed out).
        grep -E "=== RUN|--- PASS:" "$f" | grep -Eo "\bTest\w+" | sort | uniq |
            while read c; do
                jq "$(printf '.["%s"].long_time.%s |= -1' "$target" $c)" \
                    "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
            done

        # update the timecost of passed test cases.
        grep -E "\-\-\- PASS:" "$f" | grep -Eo '\bTest\w+(\s+\([0-9.]+s\))' | sed -E 's/[\(\)]//g;s/s$//g' |
            while read rec; do
                echo "$rec"
                jq "$(printf '.["%s"].long_time.%s |= if (. and . >= 0) then . else %s end' "$target" $rec)" \
                    "$resultFile" >"$resultFile".new && mv "$resultFile".new "$resultFile"
            done
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

    echo "{}" >"$DEFAILT_GO_TEST_PROBLEM_CASES_JSONFILE"
    parse_bazel_go_test_new_flaky_cases "$logPath" "$DEFAILT_GO_TEST_PROBLEM_CASES_JSONFILE"
    parse_bazel_go_test_long_cases "$logPath" "$DEFAILT_GO_TEST_PROBLEM_CASES_JSONFILE"

    echo "Output files:"
    ls $DEFAILT_GO_TEST_PROBLEM_CASES_JSONFILE \
        $DEFAULT_GO_TEST_INDEX_FILE \
        $BAZEL_FLAKY_SUMMARY_FILE \
        $BAZEL_TARGET_OUTPUT_FILE_PREFIX-*.log || true
}

main "$@"
