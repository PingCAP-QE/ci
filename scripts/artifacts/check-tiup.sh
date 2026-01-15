#! /usr/bin/env bash
set -eo pipefail

failure=0
fail_fast=false

function record_failure() {
    if [ "$1" -eq 0 ]; then
        echo "✅ success"
    else
        echo "❌ failure"
        failure=1
        if [ "$fail_fast" == "true" ]; then
            exit 1
        fi
    fi
}

function gather_results() {
    local VERSION="$1"
    local oci_registry="$2"
    local tiup_pkgs=(
        br
        cdc
        ctl
        dm-master
        dm-worker
        dmctl
        dumpling
        grafana
        pd
        pd-recover
        prometheus
        tidb
        tidb-lightning
        tiflash
        tikv
        tidb-dashboard
    )
    local source_oci_pkg_repos=(
        pingcap/ctl/package
        pingcap/monitoring/package
        pingcap/ng-monitoring/package
        pingcap/tidb-dashboard/package
        pingcap/tidb/package
        pingcap/tiflash/package
        pingcap/tiflow/package
        tikv/pd/package
        tikv/tikv/package
    )

    # if VERSION is lower then v8.4.0, then we need add pump and drainer to the tiup pkgs
    if [[ "$(printf '%s\n' "v8.4.0" "$VERSION" | sort -V | head -n1)" == "$VERSION" ]]; then
        tiup_pkgs+=(
            pump
            drainer
        )
        source_oci_pkg_repos+=(
            pingcap/tidb-binlog/package
        )
    fi
    # if VERSION is greater then v9.0.0, then we:
    # - add sync-diff-inspector to the tiup pkgs
    # - add oci repo for new cdc
    if [[ "$(printf '%s\n' "v9.0.0" "$VERSION" | sort -V | head -n1)" == "v9.0.0" ]]; then
        tiup_pkgs+=(
            sync-diff-inspector
        )
    fi

    # if VERSION is greater then 8.5.4, then we:
    # - add oci repo for new cdc
    if [[ "$(printf '%s\n' "v8.5.4" "$VERSION" | sort -V | head -n1)" == "v8.5.4" ]]; then
        source_oci_pkg_repos+=(
            pingcap/ticdc/package
        )
    fi

    touch results.yaml
    yq -i '.tiup = {}' results.yaml

    # check tiup, loop for tiup_pkgs:
    for pkg in "${tiup_pkgs[@]}"; do
        echo "🚧 check tiup $pkg:$VERSION"
        platforms=$(tiup list $pkg | grep -E "^$VERSION\b\s+")
        echo $platforms
        echo $platforms | grep "darwin/amd64" | grep "darwin/arm64" | grep "linux/amd64" | grep -q "linux/arm64"
        record_failure $?
        publish_time=$(echo "$platforms" | awk '{print $2}')
        yq -i ".tiup[\"${pkg}\"].published = \"$publish_time\"" results.yaml
    done

    # record tiup built git-sha
    echo "📝 gather tiup metadata of oci artifacts from ${oci_registry}..."
    for source_oci_pkg_repo in "${source_oci_pkg_repos[@]}"; do
        for platform in linux_amd64 linux_arm64 darwin_amd64 darwin_arm64; do
            repo="${oci_registry}/${source_oci_pkg_repo}:${VERSION}_${platform}"
            oras manifest fetch-config $repo >tmp-oci-artifact-config.yaml
            git_sha=$(yq .'["net.pingcap.tibuild.git-sha"]' tmp-oci-artifact-config.yaml)
            oci_published_at=$(oras manifest fetch $repo | yq '.annotations["org.opencontainers.image.created"]')
            for f in $(yq '.["net.pingcap.tibuild.tiup"][].file' tmp-oci-artifact-config.yaml); do
                tiup_pkg_name="${f%-v*}"
                yq -i ".tiup[\"${tiup_pkg_name}\"].git_sha[\"${platform}\"] = \"$git_sha\"" results.yaml
                yq -i ".tiup[\"${tiup_pkg_name}\"].oci_published[\"${platform}\"] = \"$oci_published_at\"" results.yaml
            done
            rm -rf tmp-oci-artifact-config.yaml
        done
    done
    echo "✅ gathered tiup metadata of oci artifacts from ${oci_registry}."
}

##### check the results files
function check_results() {
    # check tiup git_sha and published time
    for pkg in $(yq '.tiup | keys | .[]' results.yaml); do
        echo "🚧 check tiup built on git-sha for $pkg ..."
        yq -e ".tiup[\"$pkg\"].git_sha | map(.) | unique | length == 1" results.yaml
        record_failure $?

        echo "🚧 check tiup published time for $pkg ..."
        yq -e ".tiup[\"$pkg\"].published > (.tiup[\"$pkg\"].oci_published | map(.) | sort | .[0])" results.yaml
        record_failure $?
    done
}

function main() {
    tiup --version || {
        echo "💣 No tiup found in PATH"
        exit 1
    }

    check_version="$1"
    check_mirror="$2"
    oci_registry="${3:-us-docker.pkg.dev/pingcap-testing-account/hub}"
    fail_fast="${4:-false}"

    tiup mirror set $check_mirror

    gather_results "$check_version" "$oci_registry" && check_results

    if [ $failure -eq 0 ]; then
        echo '======='
        echo "🏅🏅🏅 check success"
        exit 0
    else
        echo '======='
        echo "❌❌❌ check failure"
        exit 1
    fi
}

main "$@"
