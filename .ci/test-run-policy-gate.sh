#!/usr/bin/env bash
# Tests for .ci/run-policy-gate.sh, the base-revision fence around the policy
# gates.
#
# A presubmit checks out the pull request's merge commit. Without the fence a PR
# that widens a pod spec and neuters the gate is judged by its own copy and
# reports a green check; the fence makes the gate come from the base revision
# while the pod templates under test still come from the PR.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v yq >/dev/null 2>&1; then
  echo "yq (mikefarah/yq v4) is required" >&2
  exit 1
fi
if ! command -v git >/dev/null 2>&1; then
  echo "git is required" >&2
  exit 1
fi

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1" >&2; failures=$((failures + 1)); }

cp -R "${repo_root}/.ci" "${fixture}/.ci"
mkdir -p "${fixture}/jenkins/jobs/example/repo/latest/demo"
cat >"${fixture}/jenkins/jobs/example/repo/latest/demo/pod.yaml" <<'YAML'
apiVersion: v1
kind: Pod
spec:
  automountServiceAccountToken: false
  containers:
    - name: runner
      image: ghcr.io/pingcap-qe/ci/jenkins:v2026.3.22-4-gd9d1135
YAML

cd "${fixture}"
git init -q .
git config user.email test@example.com
git config user.name test
git add -A
git commit -q -m base
base_sha="$(git rev-parse HEAD)"

# The PR widens the pod and neuters the gate it would be judged by.
python3 - <<'PY'
import pathlib
p = pathlib.Path("jenkins/jobs/example/repo/latest/demo/pod.yaml")
t = p.read_text().replace(
    "    - name: runner\n      image:",
    "    - name: runner\n      securityContext:\n        privileged: true\n      image:",
)
p.write_text(t)
pathlib.Path(".ci/verify-pod-privilege-policy.sh").write_text("#!/usr/bin/env bash\nexit 0\n")
PY
git add -A
git commit -q -m "widen pod and neuter gate"

if bash .ci/verify-pod-privilege-policy.sh >/dev/null 2>&1; then
    pass "the pull request's own copy of the gate is fooled (documents the bypass)"
else
    fail "expected the neutered in-tree gate to pass"
fi

if PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="HEAD" \
    bash .ci/run-policy-gate.sh pod-privilege >"${fixture}/out" 2>&1; then
    fail "the fenced gate accepted a privileged pod"
elif ! grep -q 'privileged' "${fixture}/out"; then
    fail "the fenced gate failed for the wrong reason: $(tail -2 "${fixture}/out" | head -1)"
else
    pass "the fenced gate blocks the privileged pod"
fi

# The previous base revision, unchanged, passes through the same fence.
git checkout -q "${base_sha}" -- jenkins/jobs/example/repo/latest/demo/pod.yaml .ci/verify-pod-privilege-policy.sh
git commit -q -am "revert the pod and the gate" || true
if PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="HEAD" \
    bash .ci/run-policy-gate.sh pod-privilege >"${fixture}/out2" 2>&1; then
    pass "the fenced gate accepts an unchanged pod"
else
    fail "the fenced gate rejected an unchanged pod: $(tail -2 "${fixture}/out2" | head -1)"
fi

# Without a base revision the fence refuses instead of falling back to the
# pull request's copy.
if env -u PULL_BASE_SHA bash .ci/run-policy-gate.sh pod-privilege >"${fixture}/out3" 2>&1; then
    fail "the fence ran without PULL_BASE_SHA"
elif grep -q 'PULL_BASE_SHA is required' "${fixture}/out3"; then
    pass "the fence refuses to run without a base revision"
else
    fail "unexpected failure without a base revision: $(head -1 "${fixture}/out3")"
fi

# The presubmit materialises the runner into $TMPDIR, so it has to locate the
# repository from git rather than from its own path.
runner_copy="$(mktemp -d)/run-policy-gate.sh"
cp .ci/run-policy-gate.sh "${runner_copy}"
if PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="HEAD" \
    bash "${runner_copy}" pod-privilege >"${fixture}/out4" 2>&1; then
    pass "the runner works when it is executed from outside the repository"
else
    fail "the runner failed when executed from outside the repository: $(tail -2 "${fixture}/out4" | head -1)"
fi

if [ "$failures" -ne 0 ]; then
    echo "run-policy-gate tests failed: $failures" >&2
    exit 1
fi
echo "run-policy-gate tests passed."
