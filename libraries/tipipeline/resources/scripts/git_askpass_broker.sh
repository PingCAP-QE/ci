#!/bin/sh
# GIT_ASKPASS implementation backed by the in-cluster GitHub token broker.
#
# Git invokes this script as: <script> "Username for 'https://github.com/org/repo.git': "
# The script mints (or reuses) a GitHub App installation token for the
# repository in the prompt and prints the credential git asked for. The App
# private key never reaches the agent: the pod ServiceAccount is the identity.
#
# Environment:
#   TIPIPELINE_BROKER_URL          broker base URL (required)
#   TIPIPELINE_BROKER_REPO         fallback owner/repo when the prompt has no path
#   TIPIPELINE_BROKER_PERMISSIONS  JSON permissions, default {"contents":"read"}
#   TIPIPELINE_BROKER_CACHE        token cache file (required)
#   TIPIPELINE_BROKER_TOKEN_FILE   caller ServiceAccount token path
#   TIPIPELINE_BROKER_TTL_SECONDS  cache lifetime, default 3000 (50 minutes)
set -eu

prompt="${1-}"
case "${prompt}" in
    Username*) field=username ;;
    Password*) field=password ;;
    *) exit 1 ;;
esac

broker_url="${TIPIPELINE_BROKER_URL:?TIPIPELINE_BROKER_URL is required}"
cache_file="${TIPIPELINE_BROKER_CACHE:?TIPIPELINE_BROKER_CACHE is required}"
# Brace literals cannot be used inside ${VAR:-...} (the shell closes the
# expansion at the first '}'), so default them explicitly.
permissions="${TIPIPELINE_BROKER_PERMISSIONS-}"
[ -n "${permissions}" ] || permissions='{"contents":"read"}'
token_file="${TIPIPELINE_BROKER_TOKEN_FILE:-/var/run/secrets/kubernetes.io/serviceaccount/token}"
ttl="${TIPIPELINE_BROKER_TTL_SECONDS:-3000}"

repo_from_prompt() {
    url=$(printf '%s' "${prompt}" | sed -n "s/.*'\([^']*\)'.*/\1/p")
    [ -n "${url}" ] || return 1
    path=$(printf '%s' "${url}" \
        | sed -e 's#^[A-Za-z][A-Za-z0-9+.-]*://##' \
              -e 's#^[^/]*##' \
              -e 's#^/*##' \
              -e 's#/*$##' \
              -e 's#\.git$##')
    owner=$(printf '%s' "${path}" | cut -d/ -f1)
    name=$(printf '%s' "${path}" | cut -d/ -f2)
    [ -n "${owner}" ] && [ -n "${name}" ] || return 1
    printf '%s/%s' "${owner}" "${name}"
}

repo=$(repo_from_prompt || true)
[ -n "${repo}" ] || repo="${TIPIPELINE_BROKER_REPO:-}"
if [ -z "${repo}" ]; then
    echo "git-askpass(broker): cannot determine owner/repository from '${prompt}'" >&2
    exit 1
fi

cached_token() {
    [ -f "${cache_file}" ] || return 1
    expiry=$(sed -n '1p' "${cache_file}")
    token=$(sed -n '2p' "${cache_file}")
    [ -n "${expiry}" ] && [ -n "${token}" ] || return 1
    [ "$(date +%s)" -lt "${expiry}" ] || return 1
    printf '%s' "${token}"
}

mint_token() {
    caller=$(cat "${token_file}")
    response=$(curl -fsS --max-time 10 -X POST "${broker_url}/v1/token" \
        -H "Authorization: Bearer ${caller}" \
        -H 'Content-Type: application/json' \
        -d "{\"repo\":\"${repo}\",\"permissions\":${permissions}}")
    token=$(printf '%s' "${response}" \
        | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
    if [ -z "${token}" ]; then
        echo "git-askpass(broker): broker response has no token for ${repo}" >&2
        exit 1
    fi
    (umask 077; printf '%s\n%s\n' "$(($(date +%s) + ttl))" "${token}" > "${cache_file}")
    printf '%s' "${token}"
}

token=$(cached_token || mint_token)

case "${field}" in
    username) printf '%s\n' 'x-access-token' ;;
    password) printf '%s\n' "${token}" ;;
esac
