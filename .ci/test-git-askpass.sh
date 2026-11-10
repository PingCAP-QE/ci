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
expect_reject "ignores non-credential prompts" "$broker" \
    "Enter passphrase for key '/root/.ssh/id_rsa': "

if [ "$failures" -ne 0 ]; then
    echo "git-askpass tests failed: $failures" >&2
    exit 1
fi
echo "git-askpass tests passed."
