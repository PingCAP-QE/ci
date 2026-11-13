#!/usr/bin/env bash
# Tests for the GIT_ASKPASS helpers in libraries/tipipeline/resources/scripts.
#
# Both helpers answer a credential prompt for whatever host git names. A pull
# request can add a `.gitmodules` entry (or a rewritten remote) that points at a
# host of its choosing, so the host parsing and the allowlist are the control
# that keeps the bot credential on GitHub.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
askpass="$repo_root/libraries/tipipeline/resources/scripts/git_askpass.sh"
broker="$repo_root/libraries/tipipeline/resources/scripts/git_askpass_broker.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1" >&2; failures=$((failures + 1)); }

# run_askpass <script> <prompt> [env assignments...]
run_askpass() {
    local script="$1" prompt="$2"
    shift 2
    # The pipelines write the helper to the workspace and run it with `sh`; do
    # the same so the test does not depend on the file mode in git.
    env -i PATH="$PATH" HOME="$tmp" "$@" sh "$script" "$prompt"
}

expect_answer() {
    local name="$1" script="$2" prompt="$3" expected="$4"
    shift 4
    local out
    if out="$(run_askpass "$script" "$prompt" "$@" 2>"$tmp/err")" && [ "$out" = "$expected" ]; then
        pass "$name"
    else
        fail "$name (got '${out}', expected '${expected}')"
    fi
}

expect_reject() {
    local name="$1" script="$2" prompt="$3"
    shift 3
    local out
    if out="$(run_askpass "$script" "$prompt" "$@" 2>"$tmp/err")"; then
        fail "$name (accepted, printed '${out}')"
    else
        pass "$name"
    fi
}

applypass_env=(TIPIPELINE_GIT_USERNAME=bot TIPIPELINE_GIT_PASSWORD=secret)

echo "git_askpass.sh"
expect_answer "answers github.com over https" "$askpass" \
    "Username for 'https://github.com': " "bot" "${applypass_env[@]}"
expect_answer "answers a github.com repository password" "$askpass" \
    "Password for 'https://x-access-token@github.com/org/repo.git': " "secret" "${applypass_env[@]}"
expect_answer "answers the in-cluster git-cdn over http" "$askpass" \
    "Password for 'http://git-cdn.cache.svc:8000/org/repo.git': " "secret" "${applypass_env[@]}"
# A remote URL that itself contains a quote makes git print a prompt with more
# than two quotes. Answering the "last quoted segment" would then answer for an
# allow-listed host while git sends the credential to the other one.
expect_reject "rejects a quoted URL that hides the real host" "$askpass" \
    "Password for 'https://evil.example/'https://github.com': " "${applypass_env[@]}"
expect_reject "rejects a quote that moves the allow-listed host into userinfo" "$askpass" \
    "Password for 'https://github.com/x'@evil.example/': " "${applypass_env[@]}"
expect_reject "rejects a foreign host" "$askpass" \
    "Password for 'https://evil.example/org/repo.git': " "${applypass_env[@]}"
expect_reject "rejects plain http to an internet host" "$askpass" \
    "Password for 'http://github.com/org/repo.git': " "${applypass_env[@]}"
expect_reject "rejects a fragment that hides the real host" "$askpass" \
    "Password for 'https://evil.example#@github.com/org/repo.git': " "${applypass_env[@]}"
expect_reject "rejects a query that hides the real host" "$askpass" \
    "Password for 'https://evil.example?x=@github.com/org/repo.git': " "${applypass_env[@]}"
expect_reject "rejects a suffix-matching host" "$askpass" \
    "Password for 'https://github.com.evil.example/org/repo.git': " "${applypass_env[@]}"
expect_reject "ignores non-credential prompts" "$askpass" \
    "Enter passphrase for key '/root/.ssh/id_rsa': " "${applypass_env[@]}"

echo "git_askpass_broker.sh"
# No broker environment is set: reaching the "TIPIPELINE_BROKER_URL is required"
# error proves the prompt passed the host checks.
broker_reaches_mint() {
    local name="$1" prompt="$2"
    if run_askpass "$broker" "$prompt" 2>"$tmp/err"; then
        fail "$name (unexpectedly succeeded)"
        return
    fi
    if grep -q 'TIPIPELINE_BROKER_URL is required' "$tmp/err"; then
        pass "$name"
    else
        fail "$name (failed before the host checks: $(head -1 "$tmp/err"))"
    fi
}
broker_reaches_mint "accepts github.com" \
    "Password for 'https://x-access-token@github.com/org/repo.git': "
broker_reaches_mint "accepts the in-cluster git-cdn" \
    "Password for 'http://git-cdn.cache.svc:8000/org/repo.git': "
expect_reject "rejects a foreign host" "$broker" \
    "Password for 'https://evil.example/org/repo.git': "
expect_reject "rejects plain http to an internet host" "$broker" \
    "Password for 'http://github.com/org/repo.git': "
expect_reject "rejects a fragment that hides the real host" "$broker" \
    "Password for 'https://evil.example#@github.com/org/repo.git': "
expect_reject "rejects a query that hides the real host" "$broker" \
    "Password for 'https://evil.example?x=@github.com/org/repo.git': "
expect_reject "rejects a quoted URL that hides the real host" "$broker" \
    "Password for 'https://evil.example/'https://github.com': "
expect_reject "rejects a quote that moves the allow-listed host into userinfo" "$broker" \
    "Password for 'https://github.com/x'@evil.example/': "
expect_reject "refuses a write-scoped permission request" "$broker" \
    "Password for 'https://github.com/org/repo.git': " \
    TIPIPELINE_BROKER_PERMISSIONS='{"contents":"write"}'
broker_reaches_mint "accepts the read-only permission set" \
    "Password for 'https://github.com/org/repo.git': "
expect_reject "ignores non-credential prompts" "$broker" \
    "Enter passphrase for key '/root/.ssh/id_rsa': "

echo "repository scope (GIT_CREDENTIAL_ALLOWED_REPOS)"
# The host allowlist keeps the credential on GitHub. The scope keeps it off
# other people's repositories, which matters because `.gitmodules` (at any
# depth) chooses the repository git asks for.
expect_answer "answers the repository the scope names" "$askpass" \
    "Password for 'https://x-access-token@github.com/pingcap/tidb.git': " "secret" \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb"
expect_answer "answers a repository under an allowed organization" "$askpass" \
    "Password for 'https://x-access-token@github.com/pingcap-inc/enterprise-extensions.git': " "secret" \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb pingcap-inc/*"
expect_answer "matches the scope case-insensitively" "$askpass" \
    "Password for 'https://x-access-token@github.com/PingCAP/TiDB.git': " "secret" \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="PINGCAP/tidb"
expect_answer "answers an scp-like submodule url under an allowed organization" "$askpass" \
    "Password for 'git@github.com:pingcap-inc/enterprise-extensions.git': " "secret" \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="pingcap-inc/*"
expect_reject "rejects a private repository outside the scope" "$askpass" \
    "Password for 'https://x-access-token@github.com/pingcap/private-repo.git': " \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb tikv/*"
expect_reject "rejects an scp-like submodule url outside the scope" "$askpass" \
    "Password for 'git@github.com:someone-else/private.git': " \
    "${applypass_env[@]}" GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb pingcap-inc/*"
expect_reject "rejects a prompt that names no repository" "$askpass" \
    "Username for 'https://github.com': " "${applypass_env[@]}" \
    GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/*"

broker_reaches_mint_scoped() {
    local name="$1" prompt="$2"
    shift 2
    if run_askpass "$broker" "$prompt" "$@" 2>"$tmp/err"; then
        fail "$name (unexpectedly succeeded)"
        return
    fi
    if grep -q 'TIPIPELINE_BROKER_URL is required' "$tmp/err"; then
        pass "$name"
    else
        fail "$name (failed before the scope check: $(head -1 "$tmp/err"))"
    fi
}
broker_reaches_mint_scoped "the broker path mints only for a scoped repository" \
    "Password for 'https://x-access-token@github.com/pingcap/tidb.git': " \
    GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb"
expect_reject "the broker path refuses a repository outside the scope" "$broker" \
    "Password for 'https://x-access-token@github.com/someone-else/private.git': " \
    GIT_CREDENTIAL_ALLOWED_REPOS="pingcap/tidb"
expect_reject "the broker path refuses a scp-like url outside the scope" "$broker" \
    "Password for 'https://x-access-token@github.com/pingcap-inc/x.git': " \
    GIT_CREDENTIAL_ALLOWED_REPOS="tidbcloud/*"

if [ "$failures" -ne 0 ]; then
    echo "git-askpass tests failed: $failures" >&2
    exit 1
fi
echo "git-askpass tests passed."
