#! /usr/bin/env bash

# ticdc_download_test_binaries_by_tag.sh will
# * download all the binaries you need for integration testing

set -euo pipefail

VERSION=${1}
OS=${2:-linux}
ARCH=${3:-amd64}

# Constants
# Allow override through environment variable, fall back to default if not set
FILE_SERVER_URL="${FILE_SERVER_URL:-http://fileserver.pingcap.net}"
TMP_DIR="tmp"
THIRD_BIN_DIR="third_bin"
BIN_DIR="bin"

# ANSI color codes
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Functions
log_green() {
	echo -e "${GREEN}$1${NC}"
}

download_file() {
	local url=$1
	local file_name=$2
	local file_path=$3
	if [[ -f "${file_path}" ]]; then
		echo "File ${file_name} already exists, skipping download"
		return
	fi
	echo ">>> Downloading ${file_name} from ${url}"
	wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "${file_path}" "${url}"
}

download_binaries() {
	log_green "Downloading binaries..."

	local pingcap_base_url="${FILE_SERVER_URL}/download/builds/pingcap"
	local tikv_base_url="${FILE_SERVER_URL}/download/builds/tikv"
	
	# Define download URLs for main components
	local tidb_url="${pingcap_base_url}/tidb/tag/${VERSION}/${OS}_${ARCH}/tidb.tar.gz"
	local tiflash_url="${pingcap_base_url}/tiflash/tag/${VERSION}/${OS}_${ARCH}/tiflash.tar.gz"
	local ctl_url="${pingcap_base_url}/ctl/tag/${VERSION}/${OS}_${ARCH}/ctl.tar.gz"
	local tikv_url="${tikv_base_url}/tikv/tag/${VERSION}/${OS}_${ARCH}/tikv.tar.gz"
	local pd_url="${tikv_base_url}/pd/tag/${VERSION}/${OS}_${ARCH}/pd.tar.gz"

	# Common URLs that are not frequently updated or changed.
	local minio_download_url="${FILE_SERVER_URL}/download/minio.tar.gz"
	local go_ycsb_download_url="${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
	local etcd_download_url="${FILE_SERVER_URL}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz"
	local jq_download_url="${FILE_SERVER_URL}/download/builds/pingcap/test/jq-1.6/jq-linux64"
	local schema_registry_url="${FILE_SERVER_URL}/download/builds/pingcap/cdc/schema-registry.tar.gz"

	# Download and extract binaries
	download_and_extract "$tidb_url" "tidb.tar.gz" "tidb-server"
	download_and_extract "$pd_url" "pd.tar.gz" "pd-server"
	download_and_extract "$tikv_url" "tikv.tar.gz" "tikv-server"
	download_and_extract "$tiflash_url" "tiflash.tar.gz"
	download_and_extract "$ctl_url" "ctl.tar.gz" "pd-ctl"
	download_and_extract "$minio_download_url" "minio.tar.gz"
	download_and_extract "$etcd_download_url" "etcd.tar.gz" "etcd-v3.4.7-linux-amd64/etcdctl"
	download_and_extract "$schema_registry_url" "schema-registry.tar.gz"

	download_file "$go_ycsb_download_url" "go-ycsb" "${THIRD_BIN_DIR}/go-ycsb"
	download_file "$jq_download_url" "jq" "${THIRD_BIN_DIR}/jq"

	chmod a+x ${THIRD_BIN_DIR}/*
}

download_and_extract() {
	local url=$1
	local file_name=$2
	local extract_path=${3:-""}

	download_file "$url" "$file_name" "${TMP_DIR}/$file_name"
	if [ -n "$extract_path" ]; then
		tar -xz --wildcards -C ${THIRD_BIN_DIR} $extract_path -f ${TMP_DIR}/$file_name
	else
		tar -xz --wildcards -C ${THIRD_BIN_DIR} -f ${TMP_DIR}/$file_name
	fi

	# Move extracted files if necessary
	case $file_name in
	"tiflash.tar.gz")
		mv ${THIRD_BIN_DIR}/tiflash ${THIRD_BIN_DIR}/_tiflash
		mv ${THIRD_BIN_DIR}/_tiflash/* ${THIRD_BIN_DIR}/ && rm -rf ${THIRD_BIN_DIR}/_tiflash
		;;
	"etcd.tar.gz")
		mv ${THIRD_BIN_DIR}/etcd-v3.4.7-linux-amd64/etcdctl ${THIRD_BIN_DIR}/
		rm -rf ${THIRD_BIN_DIR}/etcd-v3.4.7-linux-amd64
		;;
	"schema-registry.tar.gz")
		mv ${THIRD_BIN_DIR}/schema-registry ${THIRD_BIN_DIR}/_schema_registry
		mv ${THIRD_BIN_DIR}/_schema_registry/* ${THIRD_BIN_DIR}/ && rm -rf ${THIRD_BIN_DIR}/_schema_registry
		;;
	esac
}

# Main execution
cleanup() {
	rm -rf ${TMP_DIR} ${THIRD_BIN_DIR}
}

setup() {
	cleanup
	rm -rf ${BIN_DIR}
	mkdir -p ${THIRD_BIN_DIR} ${TMP_DIR} ${BIN_DIR}
}

main() {
	setup
	download_binaries
	# Move binaries to final location
	mv ${THIRD_BIN_DIR}/* ./${BIN_DIR}
	cleanup
	log_green "Download SUCCESS"
}

main
