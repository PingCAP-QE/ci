#! /usr/bin/env bash
set -euo pipefail

# fake-gcs-server
function upload_fake-gcs-server() {
    local repo_base_url="$1"
    local repo="$repo_base_url/pingcap/third-party/fake-gcs-server"
    for version in 1.54.0; do
        for arch in amd64 arm64; do
            oci_tag="v${version}_linux_${arch}"
            if oras manifest fetch "$repo:$oci_tag" >/dev/null 2>&1; then
                echo "tag $oci_tag already exists in $repo." >&2
                continue
            fi

            file_name="fake-gcs-server_${version}_Linux_${arch}.tar.gz"
            download_url="https://github.com/fsouza/fake-gcs-server/releases/download/v${version}/${file_name}"

            pushd "$(mktemp -d)"
                wget -O "$file_name" "$download_url"
                oras push --artifact-type application/gzip $repo:$oci_tag "$file_name"
            popd
        done
    done
}

# kes
function upload_kes() {
    local repo_base_url="$1"
    local repo="$repo_base_url/pingcap/third-party/kes"
    for version in 0.14.0; do
        for arch in amd64 arm64; do
            oci_tag="v${version}_linux_${arch}"
            if oras manifest fetch "$repo:$oci_tag" >/dev/null 2>&1; then
                echo "tag $oci_tag already exists in $repo." >&2
                continue
            fi

            # GitHub asset names for kes v0.14.0 are like:
            #  - kes-linux-amd64
            #  - kes-linux-arm64
            file_name="kes-linux-${arch}"
            download_url="https://github.com/minio/kes/releases/download/v${version}/${file_name}"
            pushd "$(mktemp -d)"
                wget -O kes "$download_url"
                chmod +x kes
                oras push --artifact-type application/octet-stream $repo:$oci_tag kes
            popd
        done
    done
}

function main() {
    upload_fake-gcs-server "$@"
    upload_kes "$@"
}

main "$@"
