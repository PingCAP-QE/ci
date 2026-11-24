#!/usr/bin/env bash
# Regression tests for .ci/flush-jenkins-library-cache.sh.
#
# The script deletes cache entries on a live controller, so these cases pin the
# properties that must never drift:
#   - only the named library is removed, matched by exact string equality;
#   - nothing is removed without --yes outside a terminal;
#   - anything that is not a kube or library name is refused before kubectl runs;
#   - a cache entry whose digest is not a 64-hex name is skipped, never deleted.
#
# The stub kubectl is passed through KUBECTL — not PATH — and runs the pod half
# locally against a fixture cache directory, so no cluster and no Jenkins are
# involved.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
flush_script="${repo_root}/.ci/flush-jenkins-library-cache.sh"

if [[ ! -f "${flush_script}" ]]; then
    echo "script not found: ${flush_script}" >&2
    exit 1
fi

failures=0
fail() {
    printf 'FAIL: %s\n' "$*" >&2
    failures=$((failures + 1))
}
pass() {
    printf 'ok: %s\n' "$*"
}

fixture=""
home=""
stub=""

# new_fixture creates a temporary JENKINS_HOME plus a stub kubectl, which drops
# the kube arguments and runs the command the controller would run.
new_fixture() {
    fixture="$(mktemp -d)"
    home="${fixture}/jenkins-home"
    stub="${fixture}/kubectl-stub"
    cat >"${stub}" <<'FAKE'
#!/usr/bin/env bash
while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--" ]]; then
        shift
        break
    fi
    shift
done
exec "$@"
FAKE
    chmod +x "${stub}"
}

# add_cache_entry <digest> <library> seeds one cached library the way
# pipeline-groovy-lib leaves it: a content directory and <digest>-name.txt.
add_cache_entry() {
    local digest="$1" library="$2" cache="${home}/global-libraries-cache"
    mkdir -p "${cache}/${digest}/vars"
    printf 'def checkpoint() {}\n' >"${cache}/${digest}/vars/prow.groovy"
    printf '%s' "${library}" >"${cache}/${digest}-name.txt"
}

run_flush() {
    KUBECTL="${stub}" JENKINS_HOME="${home}" bash "${flush_script}" --context test-context "$@"
}

cleanup() {
    [[ -n "${fixture}" ]] && rm -rf "${fixture}"
}
trap cleanup EXIT

tipipeline_a="1111111111111111111111111111111111111111111111111111111111111111"
tipipeline_b="2222222222222222222222222222222222222222222222222222222222222222"
tisys_c="3333333333333333333333333333333333333333333333333333333333333333"
not_a_digest="zzzz-not-a-digest"

seed() {
    new_fixture
    add_cache_entry "${tipipeline_a}" tipipeline
    add_cache_entry "${tipipeline_b}" tipipeline
    add_cache_entry "${tisys_c}" tisys
}

# --- list ---------------------------------------------------------------
seed
out="$(run_flush --list)"
if grep -q "${tipipeline_a}" <<<"${out}" && grep -q "${tisys_c}" <<<"${out}"; then
    pass "--list reports every library name with its digest"
else
    fail "--list did not report the seeded entries: ${out}"
fi
[[ -f "${home}/global-libraries-cache/${tipipeline_a}-name.txt" ]] ||
    fail "--list must not delete anything"

# --- dry run ------------------------------------------------------------
seed
out="$(run_flush --library tipipeline --dry-run)"
if grep -q "${tipipeline_a}" <<<"${out}" && grep -q "${tipipeline_b}" <<<"${out}" &&
    ! grep -q "${tisys_c}" <<<"${out}"; then
    pass "--dry-run names only the requested library's entries"
else
    fail "--dry-run picked the wrong entries: ${out}"
fi
[[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]] ||
    fail "--dry-run deleted an entry"

# --- flush one library --------------------------------------------------
seed
out="$(run_flush --library tipipeline --yes)"
if [[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]] ||
    [[ -d "${home}/global-libraries-cache/${tipipeline_b}" ]] ||
    [[ -f "${home}/global-libraries-cache/${tipipeline_a}-name.txt" ]]; then
    fail "the flush left tipipeline entries behind: ${out}"
elif [[ -d "${home}/global-libraries-cache/${tisys_c}" ]] &&
    [[ -f "${home}/global-libraries-cache/${tisys_c}-name.txt" ]]; then
    pass "the flush removes the named library and leaves the others alone"
else
    fail "the flush removed tisys as well"
fi

# --- the name is matched exactly, never as a glob or prefix -------------
seed
out="$(run_flush --library tipi --yes)"
if [[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]] &&
    ! grep -q "${tipipeline_a}" <<<"${out}"; then
    pass "a prefix of a library name matches nothing"
else
    fail "the library name was matched as a prefix or glob: ${out}"
fi

# --- a name that matches nothing ---------------------------------------
seed
if out="$(run_flush --library no-such-library --yes)"; then
    if [[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]]; then
        pass "an unknown library removes nothing"
    else
        fail "an unknown library removed an entry"
    fi
else
    fail "an unknown library must not fail the run: ${out}"
fi

# --- flush everything ---------------------------------------------------
seed
run_flush --all --yes >/dev/null
if [[ -z "$(find "${home}/global-libraries-cache" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    pass "--all empties the cache"
else
    fail "--all left entries behind: $(ls -1 "${home}/global-libraries-cache")"
fi

# --- an entry the plugin did not write is skipped ----------------------
seed
add_cache_entry "${not_a_digest}" tipipeline
if out="$(run_flush --library tipipeline --yes 2>&1)"; then
    if [[ -d "${home}/global-libraries-cache/${not_a_digest}" ]]; then
        pass "an entry whose digest is not 64 hex characters is skipped"
    else
        fail "a non-digest entry was deleted"
    fi
else
    fail "a non-digest entry must not fail the run: ${out}"
fi

# --- refusals -----------------------------------------------------------
seed
for bad_name in '../etc/passwd' 'tipi pipeline' '' 'tipi*'; do
    if run_flush --library "${bad_name}" --yes >/dev/null 2>&1; then
        fail "accepted ${bad_name:-<empty>} as a library name"
    fi
done
if [[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]]; then
    pass "invalid library names are refused before anything is deleted"
else
    fail "an invalid library name still deleted entries"
fi

seed
if run_flush --library tipipeline </dev/null >/dev/null 2>&1; then
    fail "flushed without --yes in a non-interactive shell"
elif [[ -d "${home}/global-libraries-cache/${tipipeline_a}" ]]; then
    pass "a non-interactive run without --yes refuses and deletes nothing"
else
    fail "the refusal still deleted entries"
fi

seed
if KUBECTL="${stub}" JENKINS_HOME="${home}" bash "${flush_script}" --library tipipeline --yes \
    >/dev/null 2>&1; then
    fail "ran without --context"
else
    pass "--context is required"
fi

# --- a controller without a cache directory ----------------------------
new_fixture
if out="$(run_flush --list 2>&1)"; then
    fail "a missing cache directory must fail: ${out}"
else
    pass "a controller without a cache directory fails instead of reporting success"
fi

if ((failures > 0)); then
    printf '%d failure(s)\n' "${failures}" >&2
    exit 1
fi
echo "flush-jenkins-library-cache tests passed."
