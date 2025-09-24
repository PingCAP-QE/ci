#!/usr/bin/env bash

EXEC_BIN="go run github.com/PingCAP-QE/ee-apps/publisher/cmd/publisher-cli@main"

main() {
    check_tools && refresh_tiup_packages "$@"
}

check_tools() {
    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        echo "jq is not installed. Please install jq before running this script."
        exit 1
    fi

    # check if go is installed
    if ! command -v go &> /dev/null; then
        echo "go is not installed. Please install go before running this script."
        exit 1
    fi

    # check if crane is installed
    if ! command -v crane &> /dev/null; then
        echo "crane is not installed. Please install crane before running this script."
        exit 1
    fi
}

refresh_tiup_packages() {
    local svc_url="$1"
    local branch="$2"
    local repo_base="${3:-hub.pingcap.net}"

    local platforms=(
        linux_amd64
        linux_arm64
        darwin_amd64
        darwin_arm64
    )
    local oci_repos=(
        ${repo_base}/pingcap/ctl/package
        ${repo_base}/pingcap/monitoring/package
        ${repo_base}/pingcap/ng-monitoring/package
        ${repo_base}/pingcap/ticdc/package
        ${repo_base}/pingcap/tidb-binlog/package
        ${repo_base}/pingcap/tidb-dashboard/package
        ${repo_base}/pingcap/tidb/package
        ${repo_base}/pingcap/tiflash/package
        ${repo_base}/pingcap/tiflow/package
        ${repo_base}/tikv/pd/package
        ${repo_base}/tikv/tikv/package
    )


    # reset rate limit
    $EXEC_BIN --url $svc_url tiup reset-rate-limit

    for oci_repo in ${oci_repos[@]}; do
        for platform in ${platforms[@]}; do
            oci_url="${oci_repo}:${branch}_${platform}"
            publish_and_wait $svc_url $oci_url
        done
    done
}

publish_and_wait() {
    local svc_url="$1"
    local url="$2"

    tmp_result=$(mktemp)
    if crane digest $url > /dev/null; then
        echo "🚀 Request to publish tiup packages from oci artifact: $url"

        # send event
        $EXEC_BIN --url ${svc_url} tiup request-to-publish --body "{ \"artifact_url\": \"${url}\" }" | jq '. // []' | tee $tmp_result

        # wait for request statuses
        for request_id in $(jq -r '.[]' "$tmp_result"); do
          echo "🔍 query for request id: ${request_id} ..."
          while true; do
            status=$($EXEC_BIN --url ${svc_url} tiup query-publishing-status --request-id "$request_id" | jq -r .)
            case "${status}" in
              "failed")
                echo "❌ Publishing failed"
                exit 1
                ;;
              "success")
                echo "✅ Publishing successful"
                break
                ;;
              "canceled")
                echo "💤 Publishing canceled"
                break
                ;;
              *)
                echo "⌛️ Status: ${status}"
                sleep 10
                ;;
            esac
          done
        done
    else
        echo "🤷 Artifact not found: $url, skip it."
    fi
}

# Example:
#   # 1. export the publisher service to local
#   kubectl port-forward -n apps svc/publisher-staging-mirror 8080:80
#   # 2. run the script to publish tiup packages from oci artifact
#   ./scripts/flow/rc/refresh-tiup.sh http://localhost:8080 <git-branch-name>
main "$@"
