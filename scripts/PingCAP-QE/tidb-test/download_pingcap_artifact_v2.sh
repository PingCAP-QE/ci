#! /usr/bin/env bash

# This script downloads PingCAP component artifacts via OCI registry.
# It delegates to download_pingcap_oci_artifact.sh for the actual download.

for i in "$@"; do
  case $i in
    -tidb=*|--tidb=*)
      TIDB="${i#*=}"
      shift # past argument=value
      ;;
    -pd=*|--pd=*)
      PD="${i#*=}"
      shift # past argument=value
      ;;
    -tikv=*|--tikv=*)
      TIKV="${i#*=}"
      shift # past argument=value
      ;;
    -tiflash=*|--tiflash=*)
      TIFLASH="${i#*=}"
      shift # past argument=value
      ;;
    --default)
      DEFAULT=YES
      shift # past argument with no value
      ;;
    -*|--*)
      echo "Unknown option $i"
      exit 1
      ;;
    *)
      ;;
  esac
done

echo "TIDB          = ${TIDB}"
echo "TIKV          = ${TIKV}"
echo "PD            = ${PD}"
echo "TIFLASH       = ${TIFLASH}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OCI_SCRIPT="${SCRIPT_DIR}/../../artifacts/download_pingcap_oci_artifact.sh"

function main() {
    rm -rf third_bin
    mkdir -p third_bin

    local args=()
    [[ -n "${TIDB}" ]] && args+=("--tidb=${TIDB}")
    [[ -n "${TIKV}" ]] && args+=("--tikv=${TIKV}")
    [[ -n "${PD}" ]] && args+=("--pd=${PD}")
    [[ -n "${TIFLASH}" ]] && args+=("--tiflash=${TIFLASH}")

    if [[ ${#args[@]} -gt 0 ]]; then
        pushd third_bin
        chmod +x "${OCI_SCRIPT}"
        "${OCI_SCRIPT}" "${args[@]}"
        popd
    fi

    ls -alh third_bin/
}

main "$@"
