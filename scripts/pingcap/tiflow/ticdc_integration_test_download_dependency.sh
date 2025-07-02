

#! /usr/bin/env bash

# help
# download some third party tools for br integration test
# usage: ./ticdc_integration_test_download_dependency.sh [--tikv=tikv_branch] [--pd=pd_branch] [--tiflash=tiflash_branch]
# example: ./ticdc_integration_test_download_dependency.sh --pd=master --tikv=master --tiflash=master
# binary from tibuild multiple branches pipeline
# * tikv / pd / tiflash
# third party tools download from fileserver
# * sync_diff_inspector / minio / go-ycsb / etcdctl / jq

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


echo "TIKV          = ${PD}"
echo "PD            = ${PD}"
echo "TIFLASH       = ${TIFLASH}"

file_server_url="http://fileserver.pingcap.net"

tikv_sha1_url="${file_server_url}/download/refs/pingcap/tikv/${TIKV}/sha1"
pd_sha1_url="${file_server_url}/download/refs/pingcap/pd/${PD}/sha1"
tiflash_sha1_url="${file_server_url}/download/refs/pingcap/tiflash/${TIFLASH}/sha1"

pd_sha1=$(curl "$pd_sha1_url")
tikv_sha1=$(curl "$tikv_sha1_url")
tiflash_sha1=$(curl "$tiflash_sha1_url")

# download pd / tikv / tiflash binary build from tibuid multibranch pipeline
pd_download_url="${file_server_url}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
tikv_download_url="${file_server_url}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
tiflash_download_url="${file_server_url}/download/builds/pingcap/tiflash/${TIFLASH}/${tiflash_sha1}/centos7/tiflash.tar.gz"

# download some dependencies tool binary from file server
sync_diff_url="${file_server_url}/download/builds/pingcap/cdc/sync_diff_inspector_hash-00998a9a_linux-amd64.tar.gz"
minio_url="${file_server_url}/download/minio.tar.gz"
go_ycsb_url="${file_server_url}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
etcd_url="${file_server_url}/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz"
jq_url="${file_server_url}/download/builds/pingcap/test/jq-1.6/jq-linux64"

function download() {
    local url=$1
    local file_name=$2
    local file_path=$3
    if [[ -f "${file_path}" ]]; then
        echo "file ${file_name} already exists, skip download"
        return
    fi
    echo "download ${file_name} from ${url}"
    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "${file_path}" "${url}"
}

function main() {
    rm -rf third_bin
    rm -rf tmp
    mkdir third_bin
    mkdir tmp
    download "$pd_download_url" "pd-server.tar.gz" "tmp/pd-server.tar.gz"
    tar -xz -C third_bin 'bin/*' -f tmp/pd-server.tar.gz && mv third_bin/bin/* third_bin/
    download "$tikv_download_url" "tikv-server.tar.gz" "tmp/tikv-server.tar.gz"
    tar -xz -C third_bin bin/tikv-server  -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
    download "$tiflash_download_url" "tiflash.tar.gz" "tmp/tiflash.tar.gz"
    tar -xz -C third_bin -f tmp/tiflash.tar.gz
    mv third_bin/tiflash third_bin/_tiflash
    mv third_bin/_tiflash/* third_bin && rm -rf third_bin/_tiflash

    download "$sync_diff_url" "sync_diff_inspector.tar.gz" "tmp/sync_diff_inspector.tar.gz"
    tar -xz -C third_bin -f tmp/sync_diff_inspector.tar.gz
    download "$minio_url" "minio.tar.gz" "tmp/minio.tar.gz"
    tar -xz -C third_bin -f tmp/minio.tar.gz
    download "$go_ycsb_url" "go-ycsb" "third_bin/go-ycsb"
    download "$etcd_url" "etcd.tar.gz" "tmp/etcd.tar.gz"
    tar -xz -C third_bin  etcd-v3.4.7-linux-amd64/etcdctl  -f tmp/etcd.tar.gz
    mv third_bin/etcd-v3.4.7-linux-amd64/etcdctl third_bin/ && rm -rf third_bin/etcd-v3.4.7-linux-amd64
    download "$jq_url" "jq" "third_bin/jq"
    chmod +x third_bin/*
    ls -alh third_bin/
}

main "$@"
