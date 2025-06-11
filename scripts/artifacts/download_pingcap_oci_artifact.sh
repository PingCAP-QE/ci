#! /usr/bin/env bash

set -eo pipefail

function fetch_file_from_oci_artifact() {
    local oci_url="$1"
    local to_match_file="$2"
    local repo="$(echo ${oci_url} | cut -d ':' -f 1)"

    # get the file blob digest.
    oras manifest fetch ${oci_url} | yq --prettyPrint -oy ".layers | filter(.annotations[\"org.opencontainers.image.title\"] | test \"$to_match_file\") | .[0]" >blob.yaml

    # download file
    file="$(yq .annotations[\"org.opencontainers.image.title\"] blob.yaml)"
    blob="$repo@$(yq .digest blob.yaml)"
    oras blob fetch --output $file $blob
    rm blob.yaml
    echo "$file"
}

function download() {
    local url=$1
    local to_match_file=$2
    local file_path=$3
    if [[ -f "${file_path}" ]]; then
        echo "file $(basename "${file_path}") already exists, skip download"
        return
    fi
    echo "🚀 Downloading file with name matched regex: '${to_match_file}' from ${url}"

    echo "📦 == artifact information ======="
    oras manifest fetch-config "$url"
    echo "===== artifact information =====🔚"
    local tarball_file=$(fetch_file_from_oci_artifact $url "${to_match_file}")
    mv -v "$tarball_file" "$file_path"
    echo "✅ Downloaded, saved in ${file_path}"
}

function download_and_extract_with_path() {
    local url=$1
    local to_match_file=$2
    local file_path=$3
    local path_in_archive=$4
    if [[ -e "${path_in_archive}" ]]; then
        echo "file ${path_in_archive} already exists, skip download"
        return
    fi

    download "$url" "$to_match_file" "$file_path"

    tar -zxvf "$file_path" --strip-components=0 -C $(dirname "$file_path")

    echo "📂 extract ${path_in_archive} from ${file_path} ..."
    tar -xzvf "${file_path}" "${path_in_archive}"
    rm "${file_path}"
    echo "✅ extracted ${path_in_archive} from ${file_path} ."
}

function compute_tag_platform_suffix() {
    local os="$(uname | tr '[:upper:]' '[:lower:]')"
    local arch="$(uname -m)"
    case "$arch" in
        x86_64)
            arch="amd64"
            ;;
        aarch64 | arm64)
            arch="arm64"
            ;;
        *)
            echo "Unsupported architecture: $arch"
            exit 1
            ;;
    esac
    echo "${os}_${arch}"
}

function main() {
    check_tools
    parse_cli_args "$@"

    if [[ -n "$TIDB" ]]; then
        echo "🚀 start download TiDB server"
        download_and_extract_with_path "$tidb_oci_url" '^tidb-v.+.tar.gz$' tidb.tar.gz tidb-server
        chmod +x tidb-server
        ./tidb-server -V
        echo "🎉 download TiDB server success"
    fi
    if [[ -n "$TIKV" ]]; then
        echo "🚀 start download TiKV server"
        download_and_extract_with_path "$tikv_oci_url" '^tikv-v.+.tar.gz$' tikv.tar.gz tikv-server
        chmod +x tikv-server
        ./tikv-server --version
        echo "🎉 download TiKV server success"
    fi
    if [[ -n "$TIKV_WORKER" ]]; then
        echo "🚀 start download TiKV worker"
        download_and_extract_with_path "$tikv_oci_url" '^tikv-worker-v.+.tar.gz$' tikv.tar.gz tikv-worker
        chmod +x tikv-worker
        ./tikv-worker --version
        echo "🎉 download TiKV worker success"
    fi
    if [[ -n "$PD" ]]; then
        echo "🚀 start download PD server"
        download_and_extract_with_path "$pd_oci_url" '^pd-v.+.tar.gz$' pd.tar.gz pd-server
        chmod +x pd-server
        ./pd-server --version
        echo "🎉 download PD server success"
    fi
    if [[ -n "$TIFLASH" ]]; then
        echo "🚀 start download TiFlash"
        download_and_extract_with_path "$tiflash_oci_url" '^tiflash-v.+.tar.gz$' tiflash.tar.gz tiflash
        chmod +x tiflash/tiflash
        ls -alh tiflash
        ./tiflash/tiflash --version
        echo "🎉 download TiFlash success"
    fi
}

function parse_cli_args() {
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
        -tikv-worker=*|--tikv-worker=*)
        TIKV_WORKER="${i#*=}"
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

    [[ -n "${TIDB}" ]]          && echo "TIDB        = ${TIDB}"
    [[ -n "${TIKV}" ]]          && echo "TIKV        = ${TIKV}"
    [[ -n "${TIKV_WORKER}" ]]   && echo "TIKV_WORKER = ${TIKV_WORKER}"
    [[ -n "${PD}" ]]            && echo "PD          = ${PD}"
    [[ -n "${TIFLASH}" ]]       && echo "TIFLASH     = ${TIFLASH}"

    if [[ -n $1 ]]; then
        echo "Last line of file specified as non-opt/last argument:"
        tail -1 $1
    fi

    # get the tag suffix by current runtime os and arch, it will be "[linux|darwin]_[amd64|arm64]" format.
    local tag_suffix=$(compute_tag_platform_suffix)

    registry_host="${OCI_ARTIFACT_HOST:-hub.pingcap.net}"
    tidb_oci_url="${registry_host}/pingcap/tidb/package:${TIDB}_${tag_suffix}"
    tiflash_oci_url="${registry_host}/pingcap/tiflash/package:${TIFLASH}_${tag_suffix}"
    tikv_oci_url="${registry_host}/tikv/tikv/package:${TIKV}_${tag_suffix}"
    pd_oci_url="${registry_host}/tikv/pd/package:${PD}_${tag_suffix}"
}

function check_tools() {
    command -v oras >/dev/null || { echo "Error: 'oras' not found"; exit 1; }
    command -v yq >/dev/null || { echo "Error: 'yq' not found"; exit 1; }
    command -v tar >/dev/null || { echo "Error: 'tar' not found"; exit 1; }
}

main "$@"
