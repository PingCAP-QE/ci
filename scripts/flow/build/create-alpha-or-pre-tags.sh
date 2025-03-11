#!/usr/bin/env bash

# required tools: gh jq

set -ex -o pipefail

function rename_tag() {
    local full_repo=$1
    local old_tag=$2
    local new_tag=$3

    # get commit sha from old tag.
    commit=$(gh api /repos/$full_repo/git/refs/tags/$old_tag | jq -r .object.sha)

    # create new tag.
    if gh api --method POST /repos/$full_repo/git/refs -f ref=refs/tags/$new_tag -f sha=$commit; then
        # remove the old tag
        gh api --method DELETE /repos/$full_repo/git/refs/tags/$old_tag
    fi
}

function create_tag() {
    local full_repo=$1
    local branch=$2
    local tag=$3

    # get commit sha from branch.
    commit=$(gh api /repos/$full_repo/git/refs/heads/$branch | jq -r .object.sha)

    # create tag.
    gh api --method POST /repos/$full_repo/git/refs -f ref=refs/tags/$tag -f sha=$commit
}

function main_replace_tags() {
    local old_tag=$1
    local new_tag=$2

    repos=(
        tikv/tikv
        tikv/pd
        pingcap/tidb
        pingcap/tiflash
        pingcap/tidb-binlog
        pingcap/tiflow
        pingcap/ticdc
        pingcap/monitoring
        pingcap/tidb-dashboard
        pingcap/ng-monitoring
    )

    for repo in "${repos[@]}"; do
        rename_tag $repo $old_tag $new_tag
    done
}

function main_create_tags() {
    local branch=$1
    local tag=$2

    repos=(
        tikv/tikv
        tikv/pd
        pingcap/tidb
        pingcap/tiflash
        pingcap/tidb-binlog
        pingcap/tiflow
        pingcap/ticdc
        pingcap/monitoring
        pingcap/tidb-dashboard
        pingcap/ng-monitoring#main
    )

    for repo in "${repos[@]}"; do
        # extract the repo and special branch from `repo`.
        repo=$(echo $repo | sed 's/#.*//')
        special_branch=$(echo $repo | sed 's/.*#//')
        # if special_branch is empty, use `branch` var.
        if [[ $special_branch == "" ]]; then
            create_tag $repo $branch $tag
        else
            create_tag $repo $special_branch $tag
        fi
    done
}

# main_replace_tags "v9.0.0-alpha" "v9.0.0-beta.1.pre"
# main_create_tags master "v9.0.0-beta.2.pre"
