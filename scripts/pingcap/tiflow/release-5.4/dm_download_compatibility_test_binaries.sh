#!/usr/bin/env bash

# dm_download_compatibility_test_binaries.sh will
# * download all the binaries you need for dm compatibility testing via OCI registry

# Notice:
# Please don't try the script locally,
# it downloads files for linux platform.

set -o errexit
set -o nounset
set -o pipefail

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

# Specify the download branch.
branch=${1:-release-5.4}

# Tool versions for OCI downloads
MINIO_VERSION="RELEASE.2020-02-27T00-23-05Z"

sync_diff_inspector_download_url="http://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz"
mydumper_download_url="http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz"

gh_os_download_url="https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz"

# Some temporary dir.
rm -rf tmp
rm -rf third_bin

mkdir -p third_bin
mkdir -p tmp
mkdir -p bin

color-green "Download binaries..."
(
cd third_bin
"${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh" \
--tidb="${branch}" \
--minio="${MINIO_VERSION}"
)

download "$sync_diff_inspector_download_url" "tidb-enterprise-tools-nightly-linux-amd64.tar.gz" "tmp/tidb-enterprise-tools-nightly-linux-amd64.tar.gz"
tar -xz -C third_bin tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector -f tmp/tidb-enterprise-tools-nightly-linux-amd64.tar.gz
mv third_bin/tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector third_bin/ && rm -rf third_bin/tidb-enterprise-tools-nightly-linux-amd64
download "$mydumper_download_url" "tidb-enterprise-tools-latest-linux-amd64.tar.gz" "tmp/tidb-enterprise-tools-latest-linux-amd64.tar.gz"
tar -xz -C third_bin tidb-enterprise-tools-latest-linux-amd64/bin/mydumper -f tmp/tidb-enterprise-tools-latest-linux-amd64.tar.gz
mv third_bin/tidb-enterprise-tools-latest-linux-amd64/bin/mydumper third_bin/ && rm -rf third_bin/tidb-enterprise-tools-latest-linux-amd64
download "$gh_os_download_url" "gh-ost-binary-linux-20200828140552.tar.gz" "tmp/gh-ost-binary-linux-20200828140552.tar.gz"
tar -xz -C third_bin -f tmp/gh-ost-binary-linux-20200828140552.tar.gz

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
mv third_bin/* ./bin
rm -rf bin/bin
rm -rf third_bin

color-green "Download SUCCESS"
