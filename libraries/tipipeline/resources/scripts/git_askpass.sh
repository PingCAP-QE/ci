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
# Two things bound the credential: the host (above) and, when the caller sets
# GIT_CREDENTIAL_ALLOWED_REPOS, the repository the URL names (see the
# "repository" section below). The shared library always sets the second one;
# it keeps a pull request from spending the credential on a repository it may
# not read.
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

# Extract the URL git quotes in the prompt: `... for '<url>': `.
#
# A quoted-URL match that scans for the *last* pair of quotes is attacker
# controlled: a remote whose URL itself contains a `'` makes git print
#   Password for 'https://evil.example/'https://github.com':
# and a last-quote match would answer for github.com while git sends the
# credential to evil.example. Strip the exact prompt wrapper instead, and
# refuse any URL that still contains a quote — no allow-listed Git host has one
# in its URL, so a quoted URL is either a bug or that trick.
url=""
case "${prompt}" in
    *" for '"*"': "*)
        url="${prompt#* for \'}"
        url="${url%\': *}"
        ;;
esac
if [ -z "${url}" ]; then
    url="${prompt}"
fi

# Refuse anything that can hide the real host from the parser below: a quote
# makes the URL boundary ambiguous, and a query or a fragment can smuggle an
# `@` that would otherwise look like userinfo.
case "${url}" in
    *"'"* | *"?"* | *"#"* | *' '* | *"	"*)
        echo "git-askpass: refusing a credential URL with a quote, query, fragment or whitespace: '${url}'" >&2
        exit 1
        ;;
esac

scheme=""
case "${url}" in
    *"://"*) scheme="${url%%://*}" ;;
esac

# Reduce the URL to its bare hostname:
#   https://x-access-token@github.com/org/repo.git -> github.com
#   ssh://git@git-cdn.cache.svc:8000/org/repo.git  -> git-cdn.cache.svc
#   git@github.com:org/repo.git                    -> github.com
rest="${url#*://}"
authority="${rest%%/*}"
host="${authority##*@}" # anything before the last '@' is userinfo
case "${host}" in
    '' | *[!A-Za-z0-9.:-]*)
        echo "git-askpass: refusing a malformed credential URL: '${url}'" >&2
        exit 1
        ;;
esac
host="${host%%:*}" # drop the port
host=$(printf '%s' "${host}" | tr '[:upper:]' '[:lower:]')

# git-cdn is reached over plain HTTP inside the cluster; every other allowed
# host must use https. A plain-HTTP prompt for an internet host would send the
# credential in clear text.
if [ -n "${scheme}" ] && [ "${scheme}" != "https" ] && [ "${scheme}" != "ssh" ]; then
    if [ "${host}" != "git-cdn.cache.svc" ]; then
        echo "git-askpass: refusing to send the credential over '${scheme}' to '${host}'" >&2
        exit 1
    fi
fi

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

# ---------------------------------------------------------------- repository
#
# The host allowlist keeps the credential on GitHub. It does not keep it off
# other people's repositories: the URLs a fetch follows come from the repository
# being checked out (.gitmodules at any depth, or a rewritten remote), so a pull
# request can make git ask for a private repository it should not read.
#
# The caller therefore names the repositories this scope may spend the
# credential on:
#
#   GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb pingcap/* pingcap-inc/*"
#
# Entries are `owner/repo` or `owner/*`, case-insensitive. Unset keeps the
# historical host-only behaviour for callers outside the shared library; every
# caller inside it sets the variable.
allowed_repos="${GIT_CREDENTIAL_ALLOWED_REPOS-}"

# `https://host/owner/repo.git` -> `owner/repo`; `git@host:owner/repo.git` too.
repo_from_url() {
    path="${url}"
    case "${path}" in
        *"://"*)
            path="${path#*://}"
            path="${path#*/}"
            ;;
        *@*:*)
            path="${path#*:}"
            ;;
        *)
            return 1
            ;;
    esac
    path="${path#/}"
    path="${path%/}"
    path="${path%.git}"
    owner=$(printf '%s' "${path%%/*}" | tr '[:upper:]' '[:lower:]')
    name=$(printf '%s' "${path#*/}" | tr '[:upper:]' '[:lower:]')
    [ -n "${owner}" ] && [ -n "${name}" ] && [ "${owner}" != "${name}" ] || return 1
    printf '%s/%s' "${owner}" "${name}"
}

if [ -n "${allowed_repos}" ]; then
    repo=$(repo_from_url) || {
        echo "git-askpass: refusing a credential for a request that names no repository: '${url}'" >&2
        exit 1
    }
    allowed=0
    for entry in $(printf '%s' "${allowed_repos}" | tr ',' ' '); do
        entry=$(printf '%s' "${entry}" | tr '[:upper:]' '[:lower:]')
        [ -n "${entry}" ] || continue
        case "${entry}" in
            */*)
                if [ "${entry}" = "${repo}" ]; then
                    allowed=1
                fi
                ;;
        esac
        case "${entry}" in
            */\*)
                if [ "${repo%%/*}" = "${entry%/*}" ]; then
                    allowed=1
                fi
                ;;
        esac
        [ "${allowed}" -eq 1 ] && break
    done
    if [ "${allowed}" -ne 1 ]; then
        echo "git-askpass: refusing a credential for '${repo}': not in GIT_CREDENTIAL_ALLOWED_REPOS (${allowed_repos})" >&2
        exit 1
    fi
fi

case "${prompt}" in
    Username*)
        printf '%s\n' "${TIPIPELINE_GIT_USERNAME-}"
        ;;
    Password*)
        printf '%s\n' "${TIPIPELINE_GIT_PASSWORD-}"
        ;;
esac
