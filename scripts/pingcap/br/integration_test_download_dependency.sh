
#! /usr/bin/env bash

# help
# download some third party tools for br integration test
# usage: ./integration_test_download_dependency.sh [target_branch] [tikv_branch] [pd_branch] [tiflash_branch] [ticdc_branch] [fileserver_url]
# example: ./integration_test_download_dependency.sh master master master master master https://download.pingcap.org
# binary from tibuild multiple branches pipeline
# * tikv / pd / tiflash / ticdc / importer
# third party tools download from fileserver
# * goycsb / minio / mc / kes / fake-gcs-server / brv4.0.8

set -eu

target_branch=$1
tikv_branch=$2
pd_branch=$3
tiflash_branch=$4
ticdc_branch=$5
file_server_url=$6
tikv_importer_branch="release-5.0"


tikv_sha1_url="${file_server_url}/download/refs/pingcap/tikv/${pd_branch}/sha1"
pd_sha1_url="${file_server_url}/download/refs/pingcap/pd/${tikv_branch}/sha1"
tiflash_sha1_url="${file_server_url}/download/refs/pingcap/tiflash/${tiflash_branch}/sha1"
tikv_importer_sha1_url="${file_server_url}/download/refs/pingcap/importer/${tikv_importer_branch}/sha1"
ticdc_sha1_url="${file_server_url}/download/refs/pingcap/ticdc/${ticdc_branch}/sha1"


pd_sha1=$(curl "$pd_sha1_url")
tikv_sha1=$(curl "$tikv_sha1_url")
tiflash_sha1=$(curl "$tiflash_sha1_url")
tikv_importer_sha1=$(curl "$tikv_importer_sha1_url")
ticdc_sha1=$(curl "$ticdc_sha1_url")

# download pd / tikv / tiflash binary build from tibuid multibranch pipeline
pd_download_url="${file_server_url}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
tikv_download_url="${file_server_url}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
tiflash_download_url="${file_server_url}/download/builds/pingcap/tiflash/${tiflash_branch}/${tiflash_sha1}/centos7/tiflash.tar.gz"
tikv_importer_download_url="${file_server_url}/download/builds/pingcap/importer/${tikv_importer_sha1}/centos7/importer.tar.gz"
ticdc_download_url="${file_server_url}/download/builds/pingcap/ticdc/${ticdc_sha1}/centos7/ticdc-linux-amd64.tar.gz"


# download some dependencies tool binary from file server

minio_url="${file_server_url}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/minio"
go_ycsb_url="${file_server_url}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
minio_cli_url="${file_server_url}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/mc"
kes_url="${file_server_url}/download/kes"
fake_gcs_server_url="${file_server_url}/download/builds/fake-gcs-server"
brv_url="${file_server_url}/download/builds/brv4.0.8"

function download() {
    local url=$1
    local file_name=$2
    local file_path=$3
    if [[ -f "${file_path}" ]]; then
        echo "file ${file_name} already exists, skip download"
        return
    fi
    echo "download ${file_name} from ${url}"
    curl -C - --retry 3 -f -L -o "${file_path}" "${url}"
}

function main() { 
    rm -rf third_bin
    rm -rf tmp
    mkdir third_bin
    mkdir tmp
    download "$pd_download_url" "pd-server.tar.gz" "tmp/pd-server.tar.gz"
    tar -xz -C third_bin 'bin/*' -f tmp/pd-server.tar.gz && mv third_bin/bin/* third_bin/
    download "$tikv_download_url" "tikv-server.tar.gz" "tmp/tikv-server.tar.gz"
    # tar -xz -C third_bin bin/tikv-server  -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
    # tar -xz -C third_bin bin/tikv-ctl  -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-ctl third_bin/
    tar -xz -C third_bin 'bin/*' -f tmp/tikv-server.tar.gz && mv third_bin/bin/* third_bin/
    download "$tiflash_download_url" "tiflash.tar.gz" "tmp/tiflash.tar.gz"
    tar -xz -C third_bin -f tmp/tiflash.tar.gz
    mv third_bin/tiflash third_bin/_tiflash
    mv third_bin/_tiflash/* third_bin && rm -rf third_bin/_tiflash
    download "$tikv_importer_download_url" "importer.tar.gz" "tmp/importer.tar.gz"
    tar -xz -C third_bin bin/tikv-importer  -f tmp/importer.tar.gz && mv third_bin/bin/tikv-importer third_bin/
    download "$ticdc_download_url" "ticdc-linux-amd64.tar.gz" "tmp/ticdc-linux-amd64.tar.gz"
    tar -xz -C third_bin -f tmp/ticdc-linux-amd64.tar.gz && mv third_bin/ticdc-linux-amd64/bin/* third_bin/ && rm -rf third_bin/ticdc-linux-amd64

    download "$minio_url" "minio" "third_bin/minio"
    download "$go_ycsb_url" "go-ycsb" "third_bin/go-ycsb"
    download "$minio_cli_url" "mc" "third_bin/mc"
    download "$kes_url" "kes" "third_bin/kes"
    download "$fake_gcs_server_url" "fake-gcs-server" "third_bin/fake-gcs-server"
    download "$brv_url" "brv4.0.8" "third_bin/brv4.0.8"

    chmod +x third_bin/*
    ls -alh third_bin/
}

main "$@"