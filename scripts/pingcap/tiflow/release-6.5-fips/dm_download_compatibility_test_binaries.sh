#!/usr/bin/env bash

# dm_download_compatibility_test_binaries.sh will
# * download all the binaries you need for dm compatibility testing

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

# PingCAP file server URL.
file_server_url="http://fileserver.pingcap.net"

# All download links.
sync_diff_inspector_download_url="http://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz"
mydumper_download_url="http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz"

gh_os_download_url="https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz"
minio_download_url="${file_server_url}/download/minio.tar.gz"

# Some temporary dir.
rm -rf tmp
rm -rf third_bin

mkdir -p third_bin
mkdir -p tmp
mkdir -p bin

color-green "Download binaries..."
download_from_oci "pingcap/tidb" 'tidb-v[^"]*.tar.gz'
download "$sync_diff_inspector_download_url" "tidb-enterprise-tools-nightly-linux-amd64.tar.gz" "tmp/tidb-enterprise-tools-nightly-linux-amd64.tar.gz"
tar -xz -C third_bin tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector -f tmp/tidb-enterprise-tools-nightly-linux-amd64.tar.gz
mv third_bin/tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector third_bin/ && rm -rf third_bin/tidb-enterprise-tools-nightly-linux-amd64
download "$mydumper_download_url" "tidb-enterprise-tools-latest-linux-amd64.tar.gz" "tmp/tidb-enterprise-tools-latest-linux-amd64.tar.gz"
tar -xz -C third_bin tidb-enterprise-tools-latest-linux-amd64/bin/mydumper -f tmp/tidb-enterprise-tools-latest-linux-amd64.tar.gz
mv third_bin/tidb-enterprise-tools-latest-linux-amd64/bin/mydumper third_bin/ && rm -rf third_bin/tidb-enterprise-tools-latest-linux-amd64
download "$minio_download_url" "minio.tar.gz" "tmp/minio.tar.gz"
tar -xz -C third_bin -f tmp/minio.tar.gz
download "$gh_os_download_url" "gh-ost-binary-linux-20200828140552.tar.gz" "tmp/gh-ost-binary-linux-20200828140552.tar.gz"
tar -xz -C third_bin -f tmp/gh-ost-binary-linux-20200828140552.tar.gz

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
mv third_bin/* ./bin
rm -rf bin/bin
rm -rf third_bin

color-green "Download SUCCESS"
