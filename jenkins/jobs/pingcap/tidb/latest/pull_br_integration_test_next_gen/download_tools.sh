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

function download_localstack() {
    if [[ -f "localstack" ]]; then
        echo "localstack already exists, skip download"
        return
    fi
    localstack_url="https://github.com/localstack/localstack-cli/releases/download/v3.7.0/localstack-cli-3.7.0-linux-amd64-onefile.tar.gz"
    echo "🚀 Downloading localstack from ${localstack_url}"
    wget --retry-connrefused --waitretry=1 --read-timeout=20 --tries=5 -O "localstack-cli.tar.gz" "$localstack_url"
    tar -xzf "localstack-cli.tar.gz"
    rm -f "localstack-cli.tar.gz"
    chmod +x localstack
    echo "✅ Downloaded localstack"
}

function main() {
    # download_brv
    # download_fake_gcs_server
    # download_go_ycsb
    # download_kes
    download_localstack
    # download_minio
    # download_tikv_importer (deprecated: tikv-importer is no longer needed)
}

main "$@"
