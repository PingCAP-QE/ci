#! /usr/bin/env bash
set -euo pipefail

# fake-gcs-server
function upload_fake-gcs-server() {
    local repo_base_url="$1"
    local repo="$repo_base_url/pingcap/third-party/fake-gcs-server"
    for version in 1.54.0; do
        for arch in amd64 arm64; do
            file_name="fake-gcs-server_${version}_Linux_${arch}.tar.gz"
            download_url="https://github.com/fsouza/fake-gcs-server/releases/download/v${version}/${file_name}"
            oci_tag="v${version}_linux_${arch}"
            pushd "$(mktemp -d)"
                wget -O "$file_name" "$download_url"
                oras push --artifact-type application/gzip $repo:$oci_tag "$file_name"
            popd
        done
    done
}

function main() {
    upload_fake-gcs-server "$@"
}

main "$@"
