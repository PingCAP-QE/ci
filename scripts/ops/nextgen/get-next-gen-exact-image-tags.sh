#! /usr/bin/env bash

set -uo pipefail

check_tools() {
    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        echo "jq is not installed. Please install jq before running this script."
        exit 1
    fi

    # Check if crane is installed
    if ! command -v crane &> /dev/null; then
        echo "crane is not installed. Please install crane before running this script."
        exit 1
    fi
}

# get the last exact images for next-gen components.
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
        echo "  📦 $repo:$tag"
    elif crane digest $repo:$commit_img_tag > /dev/null; then
        echo "  📦 $repo:$tag"
        echo "    👉 $repo:$commit_img_tag"
    else
        echo "  🤷 Image $repo:$commit_img_tag not found"
        exit 1
    fi
}

fetch_all() {
    # pingcap/ticdc repo
    echo "🚀 Fetch images built from pingcap/ticdc..."
    trunk_branch=master
    release_branch=release-8.5
    img_repo="gcr.io/pingcap-public/dbaas/ticdc"
    echo "💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo "${release_branch}-next-gen"

    # pingcap/tidb repo
    echo "🚀 Fetch images built from pingcap/tidb..."
    trunk_branch=master
    release_branch=release-nextgen-20251011

    img_repo="gcr.io/pingcap-public/dbaas/tidb"
    echo "💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="gcr.io/pingcap-public/dbaas/br"
    echo "💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="gcr.io/pingcap-public/dbaas/tidb-lightning"
    echo "💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    img_repo="gcr.io/pingcap-public/dbaas/dumpling"
    echo "💿 $img_repo"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # pingcap/tiflash repo
    echo "🚀 Fetch images built from pingcap/tiflash..."
    trunk_branch=master
    release_branch=release-nextgen-20251011
    img_repo="gcr.io/pingcap-public/dbaas/tiflash"
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # pingcap/tiproxy repo
    echo "🚀 Fetch images built from pingcap/tiproxy..."
    trunk_branch=main
    release_branch=release-nextgen-20251023
    img_repo="gcr.io/pingcap-public/dbaas/tiproxy"
    fetch_next_gen_exact_tags $img_repo "$trunk_branch"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # tidbcloud/cloud-storage-engine repo
    echo "🚀 Fetch images built from tidbcloud/cloud-storage-engine..."
    trunk_branch=dedicated
    release_branch=release-nextgen-20251011
    img_repo=gcr.io/pingcap-public/dbaas/tikv
    fetch_next_gen_exact_tags $img_repo "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags $img_repo $release_branch

    # tikv/pd repo
    echo "🚀 Fetch images built from tikv/pd..."
    trunk_branch=master
    release_branch=release-nextgen-20251011
    img_repo="gcr.io/pingcap-public/dbaas/pd"
    fetch_next_gen_exact_tags "$img_repo" "${trunk_branch}-next-gen"
    fetch_next_gen_exact_tags "$img_repo" "$release_branch"

    echo "🎉🎉🎉 All gotten"
}

main() {
    check_tools && fetch_all
}

# Usage
# ./<script>.sh
main "$@"
