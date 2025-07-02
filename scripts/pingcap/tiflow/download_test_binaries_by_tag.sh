#! /usr/bin/env bash

# download_test_binaries_by_tag.sh will
# * download all the binaries you need for integration testing
# Usage:
#   ./download_test_binaries_by_tag.sh <version> [components...] [--os=<os>] [--arch=<arch>]
# Example:
#   ./download_test_binaries_by_tag.sh v8.1.0 tidb pd                        # download tidb and pd for linux/amd64
#   ./download_test_binaries_by_tag.sh v8.1.0                                # download all for linux/amd64
#   ./download_test_binaries_by_tag.sh v8.1.0 --os=darwin --arch=arm64 tidb  # specify os/arch

set -euo pipefail

# Declare associative arrays
declare -A COMPONENT_URLS
declare -A COMPONENT_EXTRACT_PATHS

# Default values
OS="linux"
ARCH="amd64"
VERSION=${1:-""}
shift 1

if [ -z "$VERSION" ]; then
	echo "Error: Version is required"
	exit 1
fi

# Parse arguments
COMPONENTS=()
while [[ $# -gt 0 ]]; do
	case $1 in
		--os=*)
			OS="${1#*=}"
			shift
			;;
		--arch=*)
			ARCH="${1#*=}"
			shift
			;;
		*)
			COMPONENTS+=("$1")
			shift
			;;
	esac
done

# Constants
# Allow override through environment variable, fall back to default if not set
FILE_SERVER_URL="${FILE_SERVER_URL:-http://fileserver.pingcap.net}"
TMP_DIR="tmp"
THIRD_BIN_DIR="third_bin"
BIN_DIR="bin"

# ANSI color codes
GREEN='\033[0;32m'
NC='\033[0m' # No Color

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

	# If no components specified, download all
	if [ ${#COMPONENTS[@]} -eq 0 ]; then
		COMPONENTS=(tidb pd tikv tiflash ctl)
	fi

	# Validate components
	for component in "${COMPONENTS[@]}"; do
		if [[ ! ${COMPONENT_URLS[$component]+_} ]]; then
			echo "Error: Invalid component '$component'"
			echo "Available components: ${!COMPONENT_URLS[@]}"
			exit 1
		fi
	done

	# Download specified components
	for component in "${COMPONENTS[@]}"; do
		local url=${COMPONENT_URLS[$component]}
		local file_name=$(basename "$url")
		local extract_path=${COMPONENT_EXTRACT_PATHS[$component]}

		echo "Downloading component: $component"
		download_and_extract "$url" "$file_name" "$extract_path"
	done

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
	esac
}

cleanup() {
	rm -rf ${TMP_DIR} ${THIRD_BIN_DIR}
}

setup() {
	cleanup
	rm -rf ${BIN_DIR}
	mkdir -p ${THIRD_BIN_DIR} ${TMP_DIR} ${BIN_DIR}
}

setup_component_configs() {
	local pingcap_base_url="${FILE_SERVER_URL}/download/builds/pingcap"
	local tikv_base_url="${FILE_SERVER_URL}/download/builds/tikv"

	COMPONENT_URLS=(
		["tidb"]="${pingcap_base_url}/tidb/tag/${VERSION}/${OS}_${ARCH}/tidb.tar.gz"
		["pd"]="${tikv_base_url}/pd/tag/${VERSION}/${OS}_${ARCH}/pd.tar.gz"
		["tikv"]="${tikv_base_url}/tikv/tag/${VERSION}/${OS}_${ARCH}/tikv.tar.gz"
		["tiflash"]="${pingcap_base_url}/tiflash/tag/${VERSION}/${OS}_${ARCH}/tiflash.tar.gz"
		["ctl"]="${pingcap_base_url}/ctl/tag/${VERSION}/${OS}_${ARCH}/ctl.tar.gz"
	)

	COMPONENT_EXTRACT_PATHS=(
		["tidb"]="tidb-server"
		["pd"]="pd-server"
		["tikv"]="tikv-server"
		["tiflash"]=""
		["ctl"]="pd-ctl"
	)
}

main() {
	setup
	setup_component_configs
	download_binaries
	# Move binaries to final location
	mv ${THIRD_BIN_DIR}/* ./${BIN_DIR}
	cleanup
	log_green "Download SUCCESS"
}

main
