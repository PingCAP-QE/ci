#!/usr/bin/env bash

# dm_download_integration_test_binaries.sh will
# * download all the binaries you need for dm integration testing

# Notice:
# Please don't try the script locally,
# it downloads files for linux platform.

set -o errexit
set -o nounset
set -o pipefail

oci_fips_branch="feature-release-6.5-fips-fips_linux_amd64"
# Note: osci_base_url is only available in the ci environment.
oci_base_url="http://dl.apps.svc"

# See https://misc.flogisoft.com/bash/tip_colors_and_formatting.
color-green() { # Green
    echo -e "\x1B[1;32m${*}\x1B[0m"
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

# Specify the download branch.
branch=${1:-release-6.5-fips}
default_target_branch="release-6.5"

# PingCAP file server URL.
file_server_url="http://fileserver.pingcap.net"

# Get sha1 based on branch name.
tidb_tools_sha1=$(curl "${file_server_url}/download/refs/pingcap/tidb-tools/master/sha1")

# All download links.
tidb_tools_download_url="${file_server_url}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz"

gh_os_download_url="https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz"
minio_download_url="${file_server_url}/download/minio.tar.gz"

# Some temporary dir.
rm -rf tmp
rm -rf third_bin

mkdir -p third_bin
mkdir -p tmp
mkdir -p bin

color-green "Download binaries..."
download_from_oci "tikv/pd" 'pd-[^"]*.tar.gz'
download_from_oci "tikv/tikv" 'tikv-v[^"]*.tar.gz'
download_from_oci "pingcap/tidb" 'tidb-v[^"]*.tar.gz'
download "$tidb_tools_download_url" "tidb-tools.tar.gz" "tmp/tidb-tools.tar.gz"
tar -xz -C third_bin 'bin/sync_diff_inspector' -f tmp/tidb-tools.tar.gz && mv third_bin/bin/sync_diff_inspector third_bin/
download "$minio_download_url" "minio.tar.gz" "tmp/minio.tar.gz"
tar -xz -C third_bin -f tmp/minio.tar.gz
download "$gh_os_download_url" "gh-ost-binary-linux-20200828140552.tar.gz" "tmp/gh-ost-binary-linux-20200828140552.tar.gz"
tar -xz -C third_bin -f tmp/gh-ost-binary-linux-20200828140552.tar.gz

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
rm -rf bin/bin
mv third_bin/* ./bin
rm -rf third_bin
rm -rf bin/bin

color-green "Download SUCCESS"
