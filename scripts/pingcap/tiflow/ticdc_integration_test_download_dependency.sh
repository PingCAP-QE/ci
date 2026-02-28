

#! /usr/bin/env bash

# help
# download some third party tools for br integration test
# usage: ./ticdc_integration_test_download_dependency.sh [--tikv=tikv_branch] [--pd=pd_branch] [--tiflash=tiflash_branch]
# example: ./ticdc_integration_test_download_dependency.sh --pd=master --tikv=master --tiflash=master
# binary from OCI registry
# * tikv / pd / tiflash
# third party tools download from OCI registry
# * sync_diff_inspector / minio / go-ycsb / etcdctl

set -eu

for i in "$@"; do
  case $i in
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

echo "TIKV          = ${TIKV}"
echo "PD            = ${PD}"
echo "TIFLASH       = ${TIFLASH}"

SYNC_DIFF_VERSION="v8.1.0"
MINIO_VERSION="RELEASE.2020-02-27T00-23-05Z"
YCSB_VERSION="v1.0.3"
ETCD_VERSION="v3.5.17"

function main() {
    rm -rf third_bin
    mkdir third_bin

    (
        cd third_bin
        "${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh" \
            --pd="${PD}" \
            --tikv="${TIKV}" \
            --tiflash="${TIFLASH}" \
            --sync-diff-inspector="${SYNC_DIFF_VERSION}" \
            --minio="${MINIO_VERSION}" \
            --ycsb="${YCSB_VERSION}" \
            --etcdctl="${ETCD_VERSION}"

        # Flatten tiflash directory so tiflash binary is directly in third_bin/
        if [ -d tiflash ]; then
            mv tiflash/* .
            rm -rf tiflash
        fi
    )

    chmod a+x third_bin/*
    ls -alh third_bin/
}

main "$@"
