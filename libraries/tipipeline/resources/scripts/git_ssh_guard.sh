#!/bin/sh
# SSH transport guard for `git submodule update --init --recursive`.
#
# Submodule URLs come from the pull request's tree, at every depth, and the fetch
# runs with the job's bot key loaded in the agent. The `.gitmodules` pre-check
# only sees the superproject's file, so the decision that matters is taken here,
# where the credential is actually spent: github.com only, and only the
# organizations the job is allowed to read. Everything else is refused before ssh
# runs.
#
# Git invokes this as `<script> <host> <command>`, for example:
#   git_ssh_guard.sh git@github.com "git-upload-pack 'pingcap/tidb.git'"
set -eu

host="${1:-}"
if [ $# -ge 1 ]; then
    shift
fi
cmd="${1:-}"

case "$host" in
    ssh://*) host="${host#ssh://}" ;;
esac
case "$host" in
    git@github.com | github.com) ;;
    *)
        echo "refusing submodule fetch: '${host}' is not github.com" >&2
        exit 1
        ;;
esac

# `git-upload-pack '<owner>/<repo>.git'`, possibly with a leading slash. Take the
# repository path out of the command so the organization can be checked.
path=""
case "$cmd" in
    *"'"*"'"*)
        path="${cmd#*\'}"
        path="${path%\'*}"
        ;;
esac
path="${path#/}"
if [ -z "$path" ]; then
    echo "refusing submodule fetch from ${host}: cannot read the repository from '${cmd}'" >&2
    exit 1
fi
owner="${path%%/*}"
owner_lc=$(printf '%s' "$owner" | tr '[:upper:]' '[:lower:]')

parent_lc=$(printf '%s' "${GIT_SUBMODULE_PARENT_ORG:-}" | tr '[:upper:]' '[:lower:]')
allowed=0
if [ -n "$parent_lc" ] && [ "$owner_lc" = "$parent_lc" ]; then
    allowed=1
else
    for entry in $(printf '%s' "${GIT_SUBMODULE_SSH_ORGS:-}" | tr ',' ' '); do
        entry_lc=$(printf '%s' "$entry" | tr '[:upper:]' '[:lower:]')
        if [ -n "$entry_lc" ] && [ "$entry_lc" = "$owner_lc" ]; then
            allowed=1
        fi
    done
fi

if [ "$allowed" -ne 1 ]; then
    echo "refusing submodule fetch: organization '${owner}' may not spend this job's SSH credential (allowed: ${parent_lc}, ${GIT_SUBMODULE_SSH_ORGS:-})" >&2
    exit 1
fi

# `GIT_SSH_GUARD_SSH` exists so tests can assert the decision without dialling out.
exec "${GIT_SSH_GUARD_SSH:-ssh}" -o StrictHostKeyChecking=yes -o BatchMode=yes "$host" "$@"
