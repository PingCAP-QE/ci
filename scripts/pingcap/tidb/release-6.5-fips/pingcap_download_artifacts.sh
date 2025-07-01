#! /usr/bin/env bash

set -o errexit
set -o pipefail

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


if [[ -n $1 ]]; then
    echo "Last line of file specified as non-opt/last argument:"
    tail -1 $1
fi

file_server_url="http://fileserver.pingcap.net"
oci_fips_branch="feature-release-6.5-fips-fips_linux_amd64"
# Note: osci_base_url is only available in the ci environment.
oci_base_url="http://dl.apps.svc"

tiflash_sha1_url="${file_server_url}/download/refs/pingcap/tiflash/${TIFLASH}/sha1"


function download() {
    local url=$1
    local file_name=$2
    local file_path=$3
    if [[ -f "${file_path}" ]]; then
        echo "file ${file_name} already exists, remove it first"
        rm -rf "${file_path}"
    fi
    echo "download ${file_name} from ${url}"
    wget -v --retry-connrefused --tries=3 -O "${file_path}" "${url}"
    ls -alh "${file_path}"
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

function main() {
    rm -rf third_bin
    rm -rf tmp
    mkdir -p third_bin
    mkdir -p tmp

    if [[ ! -z $TIDB ]]; then
        echo ">>> start download tidb "
        download_from_oci "pingcap/tidb" 'tidb-v[^"]*.tar.gz'
    fi
    if [[ ! -z $TIKV ]]; then
        echo ">>> start download tikv "
        download_from_oci "tikv/tikv" 'tikv-v[^"]*.tar.gz'
    fi
    if [[ ! -z $PD ]]; then
        echo ">>> start download pd "
        download_from_oci "tikv/pd" 'pd-[^"]*.tar.gz'
    fi
    if [[ ! -z $TIFLASH ]]; then
        echo ">>> start download tiflash "
        tiflash_sha1=$(curl -s ${tiflash_sha1_url})
        echo "tiflash ${TIFLASH} sha1 is ${tiflash_sha1}"
        tiflash_download_url="${file_server_url}/download/builds/pingcap/tiflash/${TIFLASH}/${tiflash_sha1}/centos7/tiflash.tar.gz"
        echo "TIFLASH: ${tiflash_download_url}"
        download "$tiflash_download_url" "tiflash.tar.gz" "tmp/tiflash.tar.gz"
        tar -xz -C third_bin -f tmp/tiflash.tar.gz
        mv third_bin/tiflash third_bin/_tiflash
        mv third_bin/_tiflash/* third_bin && rm -rf third_bin/_tiflash
    fi

    chmod +x third_bin/*
    ls -alh third_bin/
}

main "$@"
