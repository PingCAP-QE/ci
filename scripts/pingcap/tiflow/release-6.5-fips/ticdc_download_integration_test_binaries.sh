#! /usr/bin/env bash

# ticdc_download_integration_test_binaries.sh will
# * download all the binaries you need for integration testing

# Notice:
# Please don't try the script locally,
# it downloads files for linux platform. We only use it in docker container.

set -o errexit
set -o pipefail

# Specify which branch to be utilized for executing the test, which is
# exclusively accessible when obtaining binaries from
# http://fileserver.pingcap.net.
branch=${1:-release-6.5-fips}
default_target_branch="release-6.5"
oci_fips_branch="feature-release-6.5-fips-fips_linux_amd64"
# Note: osci_base_url is only available in the ci environment.
oci_base_url="http://dl.apps.svc"

set -o nounset

# See https://misc.flogisoft.com/bash/tip_colors_and_formatting.
color-green() { # Green
    echo -e "\x1B[1;32m${*}\x1B[0m"
}
color-green "Download binaries from branch ${branch}"


function download_from_oci() {
    local org_and_repo=$1
    local grep_pattern=$2
    local list_api="${oci_base_url}/oci-files/hub.pingcap.net/${org_and_repo}/package?tag=${oci_fips_branch}"
    local download_api="${oci_base_url}/oci-file/hub.pingcap.net/${org_and_repo}/package?tag=${oci_fips_branch}&file="

    # TODO: remove --insecure after the certificate issue is fixed
    local file_list=$(curl -s $list_api --insecure | grep -o ${grep_pattern} |  sort | uniq)

    for file in $file_list; do
        # TODO: remove --no-check-certificate after the certificate issue is fixed
        echo "download file: ${download_api}${file}"
        wget --no-check-certificate -q "${download_api}${file}" -O "tmp/$file"

        # if download successfully, extract the file
        if [ $? -eq 0 ]; then
            echo "Extracting $file..."
            tar -xzf "tmp/$file" -C "third_bin"
        else
            echo "Failed to download $file"
        fi
    done
}

function download() {
    local url=$1
    local file_name=$2
    local file_path=$3
    if [[ -f "${file_path}" ]]; then
        echo "file ${file_name} already exists, skip download"
        return
    fi
    echo ">>>"
    echo "download ${file_name} from ${url}"
    wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "${file_path}" "${url}"
}

function download_binaries() {
    color-green "Download binaries..."
    # PingCAP file server URL.
    file_server_url="http://fileserver.pingcap.net"

    download_from_oci "tikv/pd" 'pd-[^"]*.tar.gz'
    download_from_oci "tikv/tikv" 'tikv-v[^"]*.tar.gz'
    download_from_oci "pingcap/tidb" 'tidb-v[^"]*.tar.gz'

    # Get sha1 based on branch name.
    tiflash_sha1=$(curl "${file_server_url}/download/refs/pingcap/tiflash/${default_target_branch}/sha1")

    # All download links.
    tiflash_download_url="${file_server_url}/download/builds/pingcap/tiflash/${default_target_branch}/${tiflash_sha1}/centos7/tiflash.tar.gz"
    minio_download_url="${file_server_url}/download/minio.tar.gz"
    go_ycsb_download_url="${file_server_url}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
    etcd_download_url="${file_server_url}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz"
    sync_diff_inspector_url="${file_server_url}/download/builds/pingcap/cdc/sync_diff_inspector_hash-00998a9a_linux-amd64.tar.gz"
    jq_download_url="${file_server_url}/download/builds/pingcap/test/jq-1.6/jq-linux64"

    download "$tiflash_download_url" "tiflash.tar.gz" "tmp/tiflash.tar.gz"
    tar -xz -C third_bin -f tmp/tiflash.tar.gz
    mv third_bin/tiflash third_bin/_tiflash
    mv third_bin/_tiflash/* third_bin && rm -rf third_bin/_tiflash
    download "$minio_download_url" "minio.tar.gz" "tmp/minio.tar.gz"
    tar -xz -C third_bin -f tmp/minio.tar.gz

    download "$go_ycsb_download_url" "go-ycsb" "third_bin/go-ycsb"
    download "$jq_download_url" "jq" "third_bin/jq"
    download "$etcd_download_url" "etcd.tar.gz" "tmp/etcd.tar.gz"
    tar -xz -C third_bin etcd-v3.4.7-linux-amd64/etcdctl -f tmp/etcd.tar.gz
    mv third_bin/etcd-v3.4.7-linux-amd64/etcdctl third_bin/ && rm -rf third_bin/etcd-v3.4.7-linux-amd64
    download "$sync_diff_inspector_url" "sync_diff_inspector.tar.gz" "tmp/sync_diff_inspector.tar.gz"
    tar -xz -C third_bin -f tmp/sync_diff_inspector.tar.gz

    chmod a+x third_bin/*
}

# Clean temporary dir.
rm -rf tmp
rm -rf third_bin

mkdir -p third_bin
mkdir -p tmp
mkdir -p bin

download_binaries

# Copy it to the bin directory in the root directory.
rm -rf tmp
rm -rf bin/bin
mv third_bin/* ./bin
rm -rf bin/bin
rm -rf third_bin

ls -alh ./bin

color-green "Download SUCCESS"
