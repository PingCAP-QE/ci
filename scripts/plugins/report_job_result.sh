#! /usr/bin/env bash

#### Notable: should run in python container with image: hub.pingcap.net/jenkins/python3-requests:latest ###

# ============================================================
# name: 'PIPELINE_RUN_ERROR_CODE',
# 0 : 'success',
# 1 : 'checkout code fail',
# 2 : 'build fail',
# 3 : 'product test fail',
# 4 : 'flaky test error',
# 5 : 'fmt or lint fail',
# 6 : 'pipeline engine error',
# 7 : 'agent env error',
# 8 : 'other error',
# ============================================================

# function gen
# param $1: job state
# param $2: to save json path
function gen() {
    local jobState="$1"
    local savePath="$2"
    local junitUrl="$3"
    local errCode=0 # scanner will override it when the value equal 0 and job state was failed.
    local taskStartTime=$(date "+%s%3N" -d "$(stat /proc/1 | grep 'Modify: ' | sed 's/Modify: //')")
    local taskEndTime=$(date '+%s%3N')

    cat <<EOF >"${savePath}"
{
    "pipeline_name": "${JOB_NAME}",
    "pipeline_run_url": "${RUN_DISPLAY_URL}",
    "job_state": "${jobState}",
    "jenkins_build_number": "${BUILD_NUMBER}",
    "jenkins_master": "${JENKINS_URL}",
    "repo": "${ghprbGhRepository}",
    "commit_id": "${ghprbActualCommit}",
    "target_branch": "${ghprbTargetBranch}",
    "pull_request": "${ghprbPullId}",
    "pull_request_author": "${ghprbPullAuthorLogin}",
    "job_trigger": "${ghprbPullAuthorLogin}",
    "job_start_time": "${taskStartTime}",
    "job_end_time": "${taskEndTime}",
    "trigger_comment_body": "",
    "pipeline_run_error_code": ${errCode},
    "job_result_summary": "",
    "junit_report_url": "${junitUrl}",
    "pod_ready_time": "",
    "cpu_request": "",
    "memory_request": ""
}
EOF
}

# function report
# param $1: json file path
function report() {
    local jsonPath="$1"
    local fileServerUrl="${FILE_SERVER_URL:=http://fileserver.pingcap.net}"
    echo $fileServerUrl
    wget "${fileServerUrl}/download/rd-atom-agent/agent_upload_verifyci_metadata.py"

    LC_CTYPE="en_US.UTF-8" python3 agent_upload_verifyci_metadata.py ${jsonPath}
}

# function gen
# param $1: job state
# param $2: to save json path
function main() {
    local savePath="$2"

    if gen "$@"; then
        if report "${savePath}"; then
            echo "upload data succesfully."
        else
            echo "upload data failed, but ignore it!"
        fi
    else
        echo "gen data failed, but ignore it!"
    fi
}

main "$@"
