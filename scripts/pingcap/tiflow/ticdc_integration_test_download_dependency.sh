

#! /usr/bin/env bash

set -eu

target_branch=$1
tikv_branch=$2
pd_branch=$3
tiflash_branch=$4
file_server_url=$5

tikv_sha1_url=""
pd_sha1_url=""
tiflash_sha1_url=""

pd_sha1=""
tikv_sha1=""
tiflash_sha1=""

pd_download_url=""
tikv_download_url=""
tiflash_download_url=""

sync_diff_url=""
minio_url=""
go_ycsb_url=""
etcd_url=""
jq_url=""

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


}