#! /usr/bin/env bash

set -uo pipefail

check_tools() {
    # Check if all required CLI tools are installed before touching registries.
    for tool in jq crane gcloud; do
        if ! command -v $tool &> /dev/null; then
            echo "$tool is not installed. Please install $tool before running this script."
            exit 1
        fi
    done
}

# get the last exact images for TiDB X components.
fetch_next_gen_exact_tags() {
    local repo="$1"
    local tag="$2"
    local commit_sha=$(crane config $repo:$tag | jq -r '.config.Labels["net.pingcap.tibuild.git-sha"]')
    local short_commit_sha=$(echo $commit_sha | cut -c1-7)
    local commit_img_tag=""
    if [[ $repo == */tiproxy ]]; then
        commit_img_tag="$(crane ls $repo | grep -E "\-g${short_commit_sha}[0-9a-f]*$" | head -1)"
    else
        commit_img_tag="$(crane ls $repo | grep -E "\-${short_commit_sha}[0-9a-f]*" | grep -E "\bnext(-)?gen\b" | head -1)"
    fi
    if [[ -z $commit_img_tag ]]; then
        echo "    📦 $repo:$tag"
    elif crane digest $repo:$commit_img_tag > /dev/null; then
        echo "    📦 $repo:$tag"
        echo "      👉 $repo:$commit_img_tag"
    else
        echo "    🤷 Image $repo:$commit_img_tag not found"
        exit 1
    fi
}

fetch_all() {
    registry="us.gcr.io"
    common_release_branch="release-nextgen-202603"
    # Authenticate against both registries because tiproxy trunk still resolves from gcr.io
    # while the other TiDB X artifacts are stored under us.gcr.io.
    gcloud auth print-access-token | crane auth login -u oauth2accesstoken --password-stdin "$registry"

    # pingcap/ticdc repo
    echo "🚀 Fetch images built from pingcap/ticdc..."
    trunk_branch=master
    release_branch=$common_release_branch
    img_repo="${registry}/pingcap-public/tidbx/ticdc"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo "${release_branch}"

    # pingcap/tidb repo
    echo "🚀 Fetch images built from pingcap/tidb..."
    trunk_branch=master
    release_branch=$common_release_branch
    img_repo="${registry}/pingcap-public/tidbx/tidb"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="${registry}/pingcap-public/tidbx/br"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="${registry}/pingcap-public/tidbx/tidb-lightning"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="${registry}/pingcap-public/tidbx/dumpling"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # pingcap/tiflash repo
    echo "🚀 Fetch images built from pingcap/tiflash..."
    trunk_branch=master
    release_branch=$common_release_branch
    img_repo="${registry}/pingcap-public/tidbx/tiflash"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # pingcap/tiproxy repo
    echo "🚀 Fetch images built from pingcap/tiproxy..."
    trunk_branch=main
    release_branch=release-nextgen-202603
    echo "  💿 gcr.io/pingcap-public/dbaas/tiproxy"
    fetch_next_gen_exact_tags "gcr.io/pingcap-public/dbaas/tiproxy" "$trunk_branch"
    echo "  💿 us.gcr.io/pingcap-public/tidbx/tiproxy"
    fetch_next_gen_exact_tags "us.gcr.io/pingcap-public/tidbx/tiproxy" $release_branch

    # tidbcloud/cloud-storage-engine repo
    echo "🚀 Fetch images built from tidbcloud/cloud-storage-engine..."
    trunk_branch=cloud-engine
    release_branch=$common_release_branch
    img_repo="${registry}/pingcap-public/tidbx/tikv"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # tikv/pd repo
    echo "🚀 Fetch images built from tikv/pd..."
    trunk_branch=master
    release_branch=$common_release_branch
    img_repo="${registry}/pingcap-public/tidbx/pd"
    echo "  💿 $img_repo"
    fetch_next_gen_exact_tags "$img_repo" "${trunk_branch}-nextgen"
    fetch_next_gen_exact_tags "$img_repo" "$release_branch"

    echo "🎉🎉🎉 All gotten"
}

main() {
    check_tools && fetch_all
}

# Usage
# ./<script>.sh
main "$@"
