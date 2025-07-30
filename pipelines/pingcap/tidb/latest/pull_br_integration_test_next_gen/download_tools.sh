#!/usr/bin/env bash

set -euo pipefail

function download() {
    local url=$1
    local file_path=$2
    if [[ -f "${file_path}" ]]; then
        echo "file $(basename "${file_path}") already exists, skip download"
        return
    fi
    echo "🚀 Downloading file from ${url}"
    wget --retry-connrefused --waitretry=1 --read-timeout=20 --tries=5 -O "$file_path" "$url"
    echo "✅ Downloaded, saved in ${file_path}"
}

function download_and_extract_with_path() {
    local url=$1
    local file_path=$2
    local path_in_archive=$3
    if [[ -e "${path_in_archive}" ]]; then
        echo "file ${path_in_archive} already exists, skip download"
        return
    fi

    download "$url" "$file_path"

    tar -zxvf "$file_path" --strip-components=0 -C $(dirname "$file_path")

    echo "📂 extract ${path_in_archive} from ${file_path} ..."
    tar -xzvf "${file_path}" "${path_in_archive}"
    rm "${file_path}"
    echo "✅ extracted ${path_in_archive} from ${file_path} ."
}

function download_tikv_importer() {
    local file_server_url="$1"

    tikv_importer_sha1="d9a90a49b23801369cf50d9e92bdc3ae1f62ade1"
    url="${file_server_url}/download/builds/pingcap/importer/${tikv_importer_sha1}/centos7/importer.tar.gz"
    download_and_extract_with_path "$url" "importer.tar.gz" "bin/tikv-importer"
    mv -v bin/tikv-importer ./ && rmdir bin
}

function download_minio() {
    local file_server_url="$1"

    minio_download_url="${file_server_url}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/minio"
    download "$minio_download_url" "minio"
    minio_cli_url="${file_server_url}/download/builds/minio/minio/RELEASE.2020-02-27T00-23-05Z/mc"
    download "$minio_cli_url" "mc"
}

function download_go_ycsb() {
    local file_server_url="$1"

    go_ycsb_download_url="${file_server_url}/download/builds/pingcap/go-ycsb/test-br/go-ycsb"
    download "$go_ycsb_download_url" "go-ycsb"
}

function download_kes() {
    local file_server_url="$1"

    kes_url="${file_server_url}/download/kes"
    download "$kes_url" "kes"
}

function download_fake_gcs_server() {
    local file_server_url="$1"

    fake_gcs_server_url="${file_server_url}/download/builds/fake-gcs-server"
    download "$fake_gcs_server_url" "fake-gcs-server"
}

function download_brv() {
    local file_server_url="$1"

    brv_url="${file_server_url}/download/builds/brv4.0.8"
    download "$brv_url" "brv4.0.8"
}

function download_localstack() {
    local file_server_url="$1"

    localstack_url="${file_server_url}/download/localstack-cli.tar.gz"
    download_and_extract_with_path "$localstack_url" "localstack-cli.tar.gz" localstack
    mv localstack localstack_dir
    ln -s localstack_dir/localstack localstack
}

function main() {
    local file_server_url="$1"

    # download_brv "$file_server_url"
    # download_fake_gcs_server "$file_server_url"
    # download_go_ycsb "$file_server_url"
    # download_kes "$file_server_url"
    download_localstack "$file_server_url"
    # download_minio "$file_server_url"
    download_tikv_importer "$file_server_url"
}

main "$@"
