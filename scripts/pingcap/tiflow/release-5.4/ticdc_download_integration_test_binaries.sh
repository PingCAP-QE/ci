#! /usr/bin/env bash

# ticdc_download_integration_test_binaries.sh will
# * download all the binaries you need for integration testing via OCI registry

# Notice:
# Please don't try the script locally,
# it downloads files for linux platform. We only use it in docker container.

set -o errexit
set -o pipefail

# Specify which branch to be utilized for executing the test.
branch=${1:-release-5.4}

set -o nounset

# See https://misc.flogisoft.com/bash/tip_colors_and_formatting.
color-green() { # Green
echo -e "\x1B[1;32m${*}\x1B[0m"
}
color-green "Download binaries from branch ${branch}"

# Tool versions for OCI downloads
MINIO_VERSION="RELEASE.2020-02-27T00-23-05Z"
YCSB_VERSION="v1.0.3"
ETCD_VERSION="v3.5.17"
SYNC_DIFF_VERSION="v8.1.0"

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
--tikv="${branch}" \
--pd="${branch}" \
--tiflash="${branch}" \
--minio="${MINIO_VERSION}" \
--ycsb="${YCSB_VERSION}" \
--etcdctl="${ETCD_VERSION}" \
--sync-diff-inspector="${SYNC_DIFF_VERSION}"

# Flatten tiflash directory so tiflash binary is directly accessible
if [ -d tiflash ]; then
mv tiflash/* .
rm -rf tiflash
fi
)

# jq - download from GitHub releases
wget -q -O third_bin/jq "https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux64"

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
rm -rf bin/bin
mv third_bin/* ./bin
rm -rf bin/bin
rm -rf third_bin

ls -alh ./bin

color-green "Download SUCCESS"
