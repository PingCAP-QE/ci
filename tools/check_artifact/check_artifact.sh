#!/usr/bin/env bash

VERSION=$1
failure=0

function record_failure() {
    if [ "$1" -eq 0 ]; then
        echo "✅ success"
    else
        echo "❌ failure"
        failure=1
    fi
}

function gather_results() {
    : >results.yaml

    # check tiup
    for com in 'br' 'cdc' 'ctl' 'dm-master' 'dm-worker' 'dmctl' 'drainer' 'dumpling' 'grafana' 'grafana' 'pd' 'pd-recover' 'prometheus' 'prometheus' 'pump' 'tidb' 'tidb-lightning' 'tiflash' 'tikv' 'tidb-dashboard'; do
        echo "🚧 check tiup $com:$VERSION"
        platforms=$(tiup list $com | grep -E "^$VERSION\b\s+")
        echo $platforms
        echo $platforms | grep "darwin/amd64" | grep "darwin/arm64" | grep "linux/amd64" | grep -q "linux/arm64"
        record_failure $?
        publish_time=$(echo "$platforms" | awk '{print $2}')
        yq -i ".tiup[\"${com}\"].published = \"$publish_time\"" results.yaml
    done

    # record tiup built git-sha
    for source_oci_pkg_repo in \
        pingcap/ctl/package \
        pingcap/monitoring/package \
        pingcap/ng-monitoring/package \
        pingcap/tidb-binlog/package \
        pingcap/tidb-dashboard/package \
        pingcap/tidb/package \
        pingcap/tiflash/package \
        pingcap/tiflow/package \
        tikv/pd/package \
        tikv/tikv/package; do
        for platform in linux_amd64 linux_arm64 darwin_amd64 darwin_arm64; do
            repo="hub.pingcap.net/${source_oci_pkg_repo}:${VERSION}_${platform}"
            oras manifest fetch-config $repo >tmp-oci-artifact-config.yaml
            yq '.["net.pingcap.tibuild.git-sha"]' tmp-oci-artifact-config.yaml
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

    # check docker
    for com in 'br' 'dm' 'dumpling' 'ng-monitoring' 'pd' 'ticdc' 'tidb' 'tidb-binlog' 'tidb-lightning' 'tidb-monitor-initializer' 'tiflash' 'tikv'; do
        # community image
        image="hub.pingcap.net/qa/$com:$VERSION"
        echo "🚧 check container image: $image"
        for platform in linux/amd64 linux/arm64; do
            echo "🚧 check container image: $image, platform: $platform"
            oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
            record_failure $?
            publish_time=$(yq .created tmp-image-config.yaml)
            git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
            publish_time=$(echo "$platforms" | awk '{print $2}')
            yq -i ".images.community[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
            yq -i ".images.community[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
            rm -rf tmp-image-config.yaml
        done

        # enterprise image
        for image in "hub.pingcap.net/qa/$com-enterprise:$VERSION" "gcr.io/pingcap-public/dbaas/$com:$VERSION"; do
            echo "🚧 check container image: $image"
            for platform in linux/amd64 linux/arm64; do
                echo "🚧 check container image: $image, platform: $platform"
                oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
                record_failure $?
                publish_time=$(yq .created tmp-image-config.yaml)
                git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
                publish_time=$(echo "$platforms" | awk '{print $2}')
                yq -i ".images.enterprise[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
                yq -i ".images.enterprise[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
                rm -rf tmp-image-config.yaml
            done
        done
    done

    # check failpoint
    for com in 'pd' 'tidb' 'tikv'; do
        image="hub.pingcap.net/qa/$com:$VERSION-failpoint"
        echo "🚧 check container image: $image"
        for platform in linux/amd64 linux/arm64; do
            echo "🚧 check container image: $image, platform: $platform"
            oras manifest fetch-config $image --platform $platform >tmp-image-config.yaml
            record_failure $?
            publish_time=$(yq .created tmp-image-config.yaml)
            git_sha=$(yq '.config.Labels["net.pingcap.tibuild.git-sha"]' tmp-image-config.yaml)
            publish_time=$(echo "$platforms" | awk '{print $2}')
            yq -i ".images.failpoint[\"${com}\"][\"$image\"][\"$platform\"].published = \"$publish_time\"" results.yaml
            yq -i ".images.failpoint[\"${com}\"][\"$image\"][\"$platform\"].git_sha = \"$git_sha\"" results.yaml
            rm -rf tmp-image-config.yaml
        done
    done
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
    gather_results && check_results

    if [ $failure -eq 0 ]; then
        echo '======='
        echo "check success"
        exit 0
    else
        echo '======='
        echo "check failure"
        exit 1
    fi
}

main "$@"
