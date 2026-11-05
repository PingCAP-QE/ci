#!/bin/sh

# Git credential helper (GIT_ASKPASS) for the CI bot credentials.
#
# Git calls this script as:
#   <script> "Username for 'https://github.com': "
#   <script> "Password for 'https://x-access-token@github.com/org/repo.git': "
# and prints whatever the script writes to stdout.
#
# SECURITY: the helper only answers for the exact hosts in ALLOWED_HOSTS.
# The repository being checked out is not always trusted: a pull request can
# add or change `.gitmodules`, and `git submodule update --init --recursive`
# (or any fetch of a URL taken from repository content) then makes git ask for
# a credential for an attacker-controlled host. A host-agnostic helper hands
# the bot PAT/SSH credential straight to that host. Anything not allow-listed
# gets an empty answer and a non-zero exit status, so git fails the request
# instead of authenticating it.
#
# ALLOWED_HOSTS is deliberately NOT configurable from the environment: adding
# a host is a reviewed change to this shared library, not something a pipeline
# (or the repository being checked out) can turn on.
#
#   github.com          direct HTTPS to GitHub (agents without git-cdn).
#   git-cdn.cache.svc   the in-cluster git-cdn: the TKE controllers rewrite
#                       https://github.com/ to http://git-cdn.cache.svc:8000
#                       through git config `insteadOf`, so the credential
#                       prompts carry the CDN host. Its port is ignored when
#                       matching; the hostname is the security boundary.
ALLOWED_HOSTS='github.com git-cdn.cache.svc'

prompt="${1-}"

# Only credential prompts are answered; anything else (e.g. an SSH passphrase
# prompt) must never receive the HTTP credential.
case "${prompt}" in
    Username* | Password*) ;;
    *) exit 1 ;;
esac

# Extract the URL git quotes in the prompt; fall back to the raw prompt for
# unquoted forms (scp-like `git@host:path` prompts).
url=$(printf '%s' "${prompt}" | sed -n "s/.*'\([^']*\)'.*/\1/p")
[ -n "${url}" ] || url="${prompt}"

# Reduce the URL to its bare hostname:
#   https://x-access-token@github.com/org/repo.git -> github.com
#   ssh://git@git-cdn.cache.svc:8000/org/repo.git  -> git-cdn.cache.svc
#   git@github.com:org/repo.git                    -> github.com
host="${url#*://}" # drop the scheme when present
host="${host#*@}"  # drop the userinfo ("user@") when present
host="${host%%/*}" # drop the path
host="${host%%:*}" # drop the port (and the scp-like ':' separator)
host=$(printf '%s' "${host}" | tr '[:upper:]' '[:lower:]')

allowed=0
for candidate in ${ALLOWED_HOSTS}; do
    if [ "${host}" = "${candidate}" ]; then
        allowed=1
        break
    fi
done

if [ "${allowed}" -ne 1 ]; then
    echo "git-askpass: refusing to hand credentials to host '${host:-<unknown>}' (not an allow-listed Git host)" >&2
    exit 1
fi

case "${prompt}" in
    Username*)
        printf '%s\n' "${TIPIPELINE_GIT_USERNAME-}"
        ;;
    Password*)
        printf '%s\n' "${TIPIPELINE_GIT_PASSWORD-}"
        ;;
esac
