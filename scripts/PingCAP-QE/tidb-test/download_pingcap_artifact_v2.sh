#! /usr/bin/env bash

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
tidb_sha1_url="${file_server_url}/download/refs/pingcap/tidb/${TIDB}/sha1"
tikv_sha1_url="${file_server_url}/download/refs/pingcap/tikv/${TIKV}/sha1"
pd_sha1_url="${file_server_url}/download/refs/pingcap/pd/${PD}/sha1"
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

function main() {
    rm -rf third_bin
    rm -rf tmp
    mkdir -p third_bin
    mkdir -p tmp

    if [[ ! -z $TIDB ]]; then
        echo ">>> start download tidb "
        tidb_sha1=$(curl -s ${tidb_sha1_url})
        echo "tidb ${TIDB} sha1 is ${tidb_sha1}"
        tidb_download_url="${file_server_url}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
        echo "TIDB: ${tidb_download_url}"
        download "$tidb_download_url" "tidb-server.tar.gz" "tmp/tidb-server.tar.gz"
        tar -xz -C third_bin bin/tidb-server  -f tmp/tidb-server.tar.gz && mv third_bin/bin/tidb-server third_bin/
    fi
    if [[ ! -z $TIKV ]]; then
        echo ">>> start download tikv "
        tikv_sha1=$(curl -s ${tikv_sha1_url})
        echo "tikv ${TIKV} sha1 is ${tikv_sha1}"
        tikv_download_url="${file_server_url}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
        echo "TIKV: ${tikv_download_url}"
        download "$tikv_download_url" "tikv-server.tar.gz" "tmp/tikv-server.tar.gz"
        tar -xz -C third_bin bin/tikv-server  -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
    fi
    if [[ ! -z $PD ]]; then
        echo ">>> start download pd "
        pd_sha1=$(curl -s ${pd_sha1_url})
        echo "pd ${PD} sha1 is ${pd_sha1}"
        pd_download_url="${file_server_url}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
        echo "PD: ${pd_download_url}"
        download "$pd_download_url" "pd-server.tar.gz" "tmp/pd-server.tar.gz"
        tar -xz -C third_bin bin/pd-server  -f tmp/pd-server.tar.gz && mv third_bin/bin/pd-server third_bin/
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
