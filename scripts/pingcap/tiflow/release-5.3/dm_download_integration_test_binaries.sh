#!/usr/bin/env bash

# dm_download_integration_test_binaries.sh will
# * download all the binaries you need for dm integration testing via OCI registry

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

# Specify the download branch.
branch=${1:-release-5.3}

# Tool versions for OCI downloads
MINIO_VERSION="RELEASE.2020-02-27T00-23-05Z"
SYNC_DIFF_VERSION="v8.1.0"

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
--tikv="${branch}" \
--pd="${branch}" \
--minio="${MINIO_VERSION}" \
--sync-diff-inspector="${SYNC_DIFF_VERSION}"
)

# Download gh-ost from GitHub releases
wget --no-verbose --retry-connrefused --waitretry=1 -t 3 \
-O "tmp/gh-ost.tar.gz" "$gh_os_download_url"
tar -xz -C third_bin -f tmp/gh-ost.tar.gz

chmod a+x third_bin/*

# Copy it to the bin directory in the root directory.
rm -rf tmp
rm -rf bin/bin
mv third_bin/* ./bin
rm -rf third_bin
rm -rf bin/bin

color-green "Download SUCCESS"
