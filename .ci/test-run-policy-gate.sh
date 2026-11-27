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

# The PR widens the pod and neuters the gate it would be judged by. Write the
# files directly instead of editing them with python: the presubmit image is
# alpine and only guarantees bash/sed/git.
cat >jenkins/jobs/example/repo/latest/demo/pod.yaml <<'YAML'
apiVersion: v1
kind: Pod
spec:
  automountServiceAccountToken: false
  containers:
    - name: runner
      securityContext:
        privileged: true
      image: ghcr.io/pingcap-qe/ci/jenkins:v2026.3.22-4-gd9d1135
YAML
printf '#!/usr/bin/env bash\nexit 0\n' >.ci/verify-pod-privilege-policy.sh

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

# `pod-yaml` is the gate whose Pod carries the `gar-reader` Workload Identity,
# so the same fence has to cover it: a PR that breaks a template and rewrites
# the gate must still be judged by the base revision's gate, never by its own.
# CRANE_BIN is pointed at a name that does not exist so the case stays offline;
# the structural checks below run either way.
cat >jenkins/jobs/example/repo/latest/demo/pod.yaml <<'YAML'
apiVersion: v1
kind: Pod
spec:
  # An explicit null silently overrides the Pod default; the gate rejects it.
  nodeSelector:
  containers:
    - name: runner
      image: ghcr.io/pingcap-qe/ci/jenkins:v2026.3.22-4-gd9d1135
YAML
printf '#!/bin/sh\nexit 0\n' >.ci/verify-k8s-pod-yaml.sh
git add -A
git commit -q -m "break the pod template and neuter the crane gate"
pod_yaml=jenkins/jobs/example/repo/latest/demo/pod.yaml

if CRANE_BIN=__no_crane__ sh .ci/verify-k8s-pod-yaml.sh "${pod_yaml}" >/dev/null 2>&1; then
    pass "the pull request's own copy of the pod-yaml gate is fooled (documents the bypass)"
else
    fail "expected the neutered in-tree pod-yaml gate to pass"
fi

if CRANE_BIN=__no_crane__ PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="HEAD" \
    bash .ci/run-policy-gate.sh pod-yaml "${pod_yaml}" >"${fixture}/out5" 2>&1; then
    fail "the fenced pod-yaml gate accepted a template with an explicit null node"
elif ! grep -q 'explicit null nodes are not allowed' "${fixture}/out5"; then
    fail "the fenced pod-yaml gate failed for the wrong reason: $(tail -2 "${fixture}/out5" | head -1)"
else
    pass "the fenced pod-yaml gate blocks the broken template"
fi

git checkout -q "${base_sha}" -- "${pod_yaml}" .ci/verify-k8s-pod-yaml.sh
git commit -q -am "revert the template and the gate" || true
if CRANE_BIN=__no_crane__ PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="HEAD" \
    bash .ci/run-policy-gate.sh pod-yaml "${pod_yaml}" >"${fixture}/out6" 2>&1; then
    pass "the fenced pod-yaml gate accepts an unchanged template"
else
    fail "the fenced pod-yaml gate rejected an unchanged template: $(tail -2 "${fixture}/out6" | head -1)"
fi

if [ "$failures" -ne 0 ]; then
    echo "run-policy-gate tests failed: $failures" >&2
    exit 1
fi
echo "run-policy-gate tests passed."
