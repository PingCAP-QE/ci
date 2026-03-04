#! /usr/bin/env bash

# download_test_binaries_by_tag.sh will
# * download all the binaries you need for integration testing via OCI registry
# Usage:
#   ./download_test_binaries_by_tag.sh <version> [components...]
# Example:
#   ./download_test_binaries_by_tag.sh v8.1.0 tidb pd   # download tidb and pd
#   ./download_test_binaries_by_tag.sh v8.1.0            # download all

set -euo pipefail

VERSION=${1:-""}
shift 1

if [ -z "$VERSION" ]; then
	echo "Error: Version is required"
	exit 1
fi

# Parse component arguments
COMPONENTS=()
while [[ $# -gt 0 ]]; do
	case $1 in
		*)
			COMPONENTS+=("$1")
			shift
			;;
	esac
done

# Default: download all components
if [ ${#COMPONENTS[@]} -eq 0 ]; then
	COMPONENTS=(tidb pd tikv tiflash ctl)
fi

BIN_DIR="bin"

# ANSI color codes
GREEN='\033[0;32m'
NC='\033[0m' # No Color

log_green() {
	echo -e "${GREEN}$1${NC}"
}

main() {
	rm -rf "${BIN_DIR}"
	mkdir -p "${BIN_DIR}"

	# Build OCI download args based on requested components
	local oci_args=()
	for component in "${COMPONENTS[@]}"; do
		case "$component" in
			tidb)  oci_args+=("--tidb=${VERSION}") ;;
			pd)    oci_args+=("--pd=${VERSION}") ;;
			tikv)  oci_args+=("--tikv=${VERSION}") ;;
			tiflash) oci_args+=("--tiflash=${VERSION}") ;;
			ctl)   oci_args+=("--pd-ctl=${VERSION}") ;;
			*)
				echo "Error: Unknown component '${component}'"
				echo "Available components: tidb pd tikv tiflash ctl"
				exit 1
				;;
		esac
	done

	(
		cd "${BIN_DIR}"
		"${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh" "${oci_args[@]}"
		# Flatten tiflash directory so tiflash binary is directly in bin/
		if [ -d tiflash ]; then
			mv tiflash/* .
			rm -rf tiflash
		fi
	)

	log_green "Download SUCCESS"
}

main
