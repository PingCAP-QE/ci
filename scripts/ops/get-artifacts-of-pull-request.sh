#!/usr/bin/env bash
# -*- coding: utf-8 -*-

### Notice: it's depended on GitHub cli tool `gh`, it should be installed and added to `PATH` env var.

download_base_url="http://fileserver.pingcap.net/download/builds/pingcap"

# param $1 pull request url, example: https://github.com/pingcap/tidb/pull/41310
function get_post_artifacts_by_pr() {
    local pull_request_url="${1}"
    local commit_sha

    commit_sha=$(gh pr view "${pull_request_url}" --json mergeCommit --jq .mergeCommit.oid)

    # find artifacts.
    get_artifacts_of_by_commit_sha "tidb" "${commit_sha}" "tidb-server.tar.gz"               # amd64
    get_artifacts_of_by_commit_sha "test/tidb" "${commit_sha}" "tidb-linux-arm64.tar.gz"     # arm64
    get_artifacts_of_by_commit_sha "tikv" "${commit_sha}" "tikv-server.tar.gz"               # amd64
    get_artifacts_of_by_commit_sha "test/tikv" "${commit_sha}" "tikv-linux-arm64.tar.gz"     # arm64
    get_artifacts_of_by_commit_sha "pd" "${commit_sha}" "pd-server.tar.gz"                   # amd64
    get_artifacts_of_by_commit_sha "test/pd" "${commit_sha}" "pd-linux-arm64.tar.gz"         # arm64
    get_artifacts_of_by_commit_sha "tiflash/release/master" "${commit_sha}" "tiflash.tar.gz" # amd64

    return 0
}

# param $1 pull request url, example: https://github.com/pingcap/tidb/pull/41310
function get_pre_artifacts_by_pr() {
    local pull_request_url="${1}"
    local commit_sha

    commit_sha=$(gh pr view "${pull_request_url}" --json commits --jq '.commits[-1].oid')

    # find artifacts.
    get_artifacts_of_by_commit_sha "tidb-checker/pr" "${commit_sha}" "tidb-server.tar.gz" # amd64
    get_artifacts_of_by_commit_sha "tikv/pr" "${commit_sha}" "tikv-server.tar.gz"         # amd64
    get_artifacts_of_by_commit_sha "pd/pr" "${commit_sha}" "pd-server.tar.gz"             # amd64

    return 0
}

function get_artifacts_of_by_commit_sha() {
    local static_folder="${1}"
    local commit_sha="${2}"
    local base_file_name="${3}"

    local artifact_url="${download_base_url}/${static_folder}/${commit_sha}/centos7/${base_file_name}"

    is_artifact_existed "${artifact_url}" && echo "😄 ${artifact_url} ✅" || echo "🤷 ${artifact_url} ❌"
}

# param $1 url of artifact url, example: http://fileserver.pingcap.net/download/builds/pingcap/tidb/a936e8e103c5cbe34115a082d68f18dc30475f40/centos7/tidb-server.tar.gz
function is_artifact_existed() {
    local artifact_url="${1}"
    # grep "Content-Length" | grep -Eo "\d+"
    curl -s -I --fail "${artifact_url}" 1>/dev/null
}

# param $1 pull request url, example: https://github.com/pingcap/tidb/pull/41310
function main() {
    if (($# < 1)); then
        echo "Usage ${0} http://<pr-url> [merged|open]" >&2
        exit 1
    fi

    local pull_request_url="${1}"
    local type=${2:-merged}

    case "${type}" in
    merged)
        echo "The artifacts after the pull request be merged:"
        get_post_artifacts_by_pr "${pull_request_url}"
        echo "🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚"
        ;;
    open)
        echo "The latest artifacts before the pull request be merged:"
        get_pre_artifacts_by_pr "${pull_request_url}"
        echo "🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚🔚"
        ;;
    *)
        echo "only support merged or open"
        exit 1
        ;;
    esac

}

main "$@"
POSITIONAL=()
while (($# > 0)); do
    case "${1}" in
    -f | --flag)
        echo flag: "${1}"
        shift # shift once since flags have no values
        ;;
    -s | --switch)
        numOfArgs=1 # number of switch arguments
        if (($# < numOfArgs + 1)); then
            shift $#
        else
            echo "switch: ${1} with value: ${2}"
            shift $((numOfArgs + 1)) # shift 'numOfArgs + 1' to bypass switch and its value
        fi
        ;;
    *) # unknown flag/switch
        POSITIONAL+=("${1}")
        shift
        ;;
    esac
done

set -- "${POSITIONAL[@]}" # restore positional params
