#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="${ROOT_DIR}/.ci/verify-pod-privilege-policy.sh"

if ! command -v yq >/dev/null 2>&1; then
  echo "yq (mikefarah/yq v4) is required" >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is required" >&2
  exit 1
fi

POD_PATH="jenkins/jobs/pingcap/demo/job/pod.yaml"
POD_REGEX='^jenkins/jobs/pingcap/demo/job/pod\.yaml$'

write_pod() {
  local file="$1"
  local body="$2"
  mkdir -p "$(dirname "${file}")"
  printf '%s\n' "${body}" >"${file}"
}

run_case() {
  local name="$1"
  local expect="$2"
  local pod_body="$3"
  local allowlist_entry="${4:-}"

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN

  pushd "${tmp}" >/dev/null

  git init -q
  git config user.email "ci-bot@example.com"
  git config user.name "CI Bot"

  # Seed a Pod template with no privileged container so the base commit exists.
  write_pod "${POD_PATH}" 'apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example.invalid/image:1'
  git add .
  git commit -q -m "seed"
  local base_sha
  base_sha="$(git rev-parse HEAD)"

  write_pod "${POD_PATH}" "${pod_body}"
  if [[ -n "${allowlist_entry}" ]]; then
    mkdir -p .ci
    printf '# test allowlist\n%b\n' "${allowlist_entry}" >.ci/pod-privilege-policy-allowlist.txt
  fi
  git add .
  git commit -q -m "head"
  local head_sha
  head_sha="$(git rev-parse HEAD)"

  set +e
  PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="${head_sha}" bash "${SCRIPT_PATH}" >/tmp/privilege_policy_test.out 2>&1
  local rc=$?
  set -e

  popd >/dev/null

  if [[ "${expect}" == "pass" && "${rc}" -eq 0 ]]; then
    echo "[PASS] ${name}"
    return 0
  fi
  if [[ "${expect}" == "fail" && "${rc}" -ne 0 ]]; then
    echo "[PASS] ${name}"
    return 0
  fi

  echo "[FAIL] ${name}: expected ${expect}, rc=${rc}" >&2
  sed 's/^/    /' /tmp/privilege_policy_test.out >&2
  return 1
}

PLAIN_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example.invalid/image:1'

PRIVILEGED_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true'

PRIVILEGED_INIT_POD='apiVersion: v1
kind: Pod
spec:
  initContainers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true
  containers:
    - name: golang
      image: example.invalid/image:1'

NOT_PRIVILEGED_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: false
        runAsUser: 0'

failed=0
run_case "plain_pod" "pass" "${PLAIN_POD}" || failed=1
run_case "privileged_container" "fail" "${PRIVILEGED_POD}" || failed=1
run_case "privileged_init_container" "fail" "${PRIVILEGED_INIT_POD}" || failed=1
run_case "privileged_false_is_fine" "pass" "${NOT_PRIVILEGED_POD}" || failed=1
run_case "allowlisted_container" "pass" "${PRIVILEGED_POD}" \
  "${POD_REGEX}\tdockerd\t2999-12-31\ttest exemption" || failed=1
run_case "allowlist_wildcard_container" "pass" "${PRIVILEGED_INIT_POD}" \
  "${POD_REGEX}\t*\t2999-12-31\ttest exemption" || failed=1
run_case "allowlist_wrong_container" "fail" "${PRIVILEGED_POD}" \
  "${POD_REGEX}\tother-container\t2999-12-31\tdoes not match" || failed=1
run_case "allowlist_wrong_path" "fail" "${PRIVILEGED_POD}" \
  '^jenkins/jobs/pingcap/other/job/pod\.yaml$\tdockerd\t2999-12-31\tdoes not match' || failed=1
run_case "expired_allowlist_entry" "fail" "${PRIVILEGED_POD}" \
  "${POD_REGEX}\tdockerd\t2000-01-01\texpired" || failed=1
run_case "allowlist_wrong_column_order" "fail" "${PRIVILEGED_POD}" \
  "dockerd\t${POD_REGEX}\t2999-12-31\twrong column order" || failed=1

rm -f /tmp/privilege_policy_test.out

if [[ "${failed}" -ne 0 ]]; then
  echo "pod privilege policy tests FAILED" >&2
  exit 1
fi

echo "pod privilege policy tests passed"
