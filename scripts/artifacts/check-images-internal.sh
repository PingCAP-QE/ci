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

    yq -i '.images = {}' results.yaml

    # check docker
    for com in 'br' 'dm' 'dumpling' 'ng-monitoring' 'pd' 'ticdc' 'tidb' 'tidb-binlog' 'tidb-lightning' 'tidb-monitor-initializer' 'tiflash' 'tikv'; do
        # community image
        image="${oci_registry}/qa/$com:$VERSION"
        echo "🚧 check container image: $image"
        for platform in linux/amd64 linux/arm64; do
            echo "🚧 check container image: $image, platform: $platform"
            oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
            record_failure $?
            publish_time=$(yq .created tmp-image-config.yaml)
            git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
            yq -i ".images.community[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
            yq -i ".images.community[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
            rm -rf tmp-image-config.yaml
        done

        # enterprise image
        for image in "${oci_registry}/qa/$com-enterprise:$VERSION" "gcr.io/pingcap-public/dbaas/$com:$VERSION"; do
            echo "🚧 check container image: $image"
            for platform in linux/amd64 linux/arm64; do
                echo "🚧 check container image: $image, platform: $platform"
                oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
                record_failure $?
                publish_time=$(yq .created tmp-image-config.yaml)
                git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
                yq -i ".images.enterprise[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
                yq -i ".images.enterprise[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
                rm -rf tmp-image-config.yaml
            done
        done
    done

    # check failpoint
    for com in 'pd' 'tidb' 'tikv'; do
        image="${oci_registry}/qa/$com:$VERSION-failpoint"
        echo "🚧 check container image: $image"
        for platform in linux/amd64 linux/arm64; do
            echo "🚧 check container image: $image, platform: $platform"
            oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
            record_failure $?
            publish_time=$(yq .created tmp-image-config.yaml)
            git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
            yq -i ".images.failpoint[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
            yq -i ".images.failpoint[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
            rm -rf tmp-image-config.yaml
        done
    done
}

##### check the results files
function check_results() {
    # check images part
    for profile in $(yq '.images | keys | .[]' results.yaml); do
        for cm in $(yq ".images.[\"$profile\"] | keys | .[]" results.yaml); do
            for image in $(yq ".images[\"$profile\"][\"$cm\"] | keys | .[]" results.yaml); do
                echo "🚧 check image built on git-sha for $image ..."
                yq -e ".images[\"$profile\"][\"$cm\"][\"$image\"] | (.[\"linux/amd64\"].git_sha == .[\"linux/arm64\"].git_sha)" results.yaml
                record_failure $?
            done
        done
    done
}

function main() {
    check_version="$1"
    oci_registry="${2:-us-docker.pkg.dev/pingcap-testing-account/hub}"
    fail_fast="${3:-false}"

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
