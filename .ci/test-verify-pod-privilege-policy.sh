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
PROW_PATH="prow-jobs/pingcap/demo/presubmits.yaml"
PROW_REGEX='^prow-jobs/pingcap/demo/presubmits\.yaml$'

write_file() {
  local file="$1"
  local body="$2"
  mkdir -p "$(dirname "${file}")"
  printf '%s\n' "${body}" >"${file}"
}

# run_case <name> <expect> <path> <body> [allowlist-entry]
#
# The first commit seeds a plain Pod template, the second commit writes the
# case under test, so the script sees exactly one changed file.
run_case() {
  local name="$1"
  local expect="$2"
  local path="$3"
  local body="$4"
  local allowlist_entry="${5:-}"

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN

  pushd "${tmp}" >/dev/null

  git init -q
  git config user.email "ci-bot@example.com"
  git config user.name "CI Bot"

  write_file "${POD_PATH}" 'apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example.invalid/image:1'
  git add .
  git commit -q -m "seed"
  local base_sha
  base_sha="$(git rev-parse HEAD)"

  write_file "${path}" "${body}"
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

# Privileged with the ServiceAccount token explicitly switched off: the shape
# every privileged Pod template is expected to take.
PRIVILEGED_POD='apiVersion: v1
kind: Pod
spec:
  automountServiceAccountToken: false
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true'

PRIVILEGED_INIT_POD='apiVersion: v1
kind: Pod
spec:
  automountServiceAccountToken: false
  initContainers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true
  containers:
    - name: golang
      image: example.invalid/image:1'

PRIVILEGED_POD_TOKEN='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true'

PRIVILEGED_POD_TOKEN_TRUE='apiVersion: v1
kind: Pod
spec:
  automountServiceAccountToken: true
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: true'

NOT_PRIVILEGED_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: dockerd
      image: docker:29.9.3-dind
      securityContext:
        privileged: false'

RUN_AS_ROOT_CONTAINER_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example.invalid/image:1
      securityContext:
        runAsUser: 0'

RUN_AS_ROOT_POD_LEVEL='apiVersion: v1
kind: Pod
spec:
  securityContext:
    runAsUser: 0
  containers:
    - name: golang
      image: example.invalid/image:1'

HOSTPATH_POD='apiVersion: v1
kind: Pod
spec:
  containers:
    - name: golang
      image: example.invalid/image:1
  volumes:
    - name: modules
      hostPath:
        path: /lib/modules
        type: Directory'

PROW_PRIVILEGED='presubmits:
  pingcap/demo:
    - name: pull-e2e
      spec:
        containers:
          - name: dind-daemon
            image: docker:29.9.3-dind
            securityContext:
              privileged: true
          - name: runner
            image: example.invalid/image:1'

PROW_PRIVILEGED_AUTOMOUNT='presubmits:
  pingcap/demo:
    - name: pull-e2e
      spec:
        automountServiceAccountToken: false
        containers:
          - name: dind-daemon
            image: docker:29.9.3-dind
            securityContext:
              privileged: true
          - name: runner
            image: example.invalid/image:1'

PROW_PLAIN='presubmits:
  pingcap/demo:
    - name: pull-unit
      spec:
        containers:
          - name: runner
            image: example.invalid/image:1'

PROW_PERIODIC_HOSTPATH='periodics:
  - name: e2e-kind
    spec:
      containers:
        - name: runner
          image: example.invalid/image:1
      volumes:
        - name: cgroup
          hostPath:
            path: /sys/fs/cgroup
            type: Directory'

failed=0
run_case "plain_pod" "pass" "${POD_PATH}" "${PLAIN_POD}" || failed=1
run_case "privileged_container" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" || failed=1
run_case "privileged_init_container" "fail" "${POD_PATH}" "${PRIVILEGED_INIT_POD}" || failed=1
run_case "privileged_false_is_fine" "pass" "${POD_PATH}" "${NOT_PRIVILEGED_POD}" || failed=1
run_case "allowlisted_container" "pass" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "privileged\t${POD_REGEX}\tdockerd\t2999-12-31\ttest exemption" || failed=1
run_case "allowlist_wildcard_container" "pass" "${POD_PATH}" "${PRIVILEGED_INIT_POD}" \
  "privileged\t${POD_REGEX}\t*\t2999-12-31\ttest exemption" || failed=1
run_case "allowlist_wildcard_check" "pass" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "*\t${POD_REGEX}\tdockerd\t2999-12-31\ttest exemption" || failed=1
run_case "allowlist_wrong_container" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "privileged\t${POD_REGEX}\tother-container\t2999-12-31\tdoes not match" || failed=1
run_case "allowlist_wrong_path" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" \
  'privileged\t^jenkins/jobs/pingcap/other/job/pod\.yaml$\tdockerd\t2999-12-31\tdoes not match' || failed=1
run_case "expired_allowlist_entry" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "privileged\t${POD_REGEX}\tdockerd\t2000-01-01\texpired" || failed=1
run_case "allowlist_wrong_check_name" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "privleged\t${POD_REGEX}\tdockerd\t2999-12-31\ttypo" || failed=1
run_case "allowlist_legacy_four_columns" "fail" "${POD_PATH}" "${PRIVILEGED_POD}" \
  "${POD_REGEX}\tdockerd\t2999-12-31\tlegacy entry without a check column" || failed=1

# The ServiceAccount token is a separate rule: an allowlisted privileged
# container still must not mount it.
run_case "privileged_without_automount" "fail" "${POD_PATH}" "${PRIVILEGED_POD_TOKEN}" || failed=1
run_case "privileged_with_automount_true" "fail" "${POD_PATH}" "${PRIVILEGED_POD_TOKEN_TRUE}" || failed=1
run_case "privileged_allowlisted_without_automount" "fail" "${POD_PATH}" "${PRIVILEGED_POD_TOKEN}" \
  "privileged\t${POD_REGEX}\tdockerd\t2999-12-31\ttest exemption" || failed=1
run_case "automount_allowlisted" "pass" "${POD_PATH}" "${PRIVILEGED_POD_TOKEN}" \
  "privileged\t${POD_REGEX}\tdockerd\t2999-12-31\tdind sidecar\nautomount\t${POD_REGEX}\t*\t2999-12-31\tjob authenticates to the API with this token" || failed=1
run_case "plain_pod_without_automount" "pass" "${POD_PATH}" "${PLAIN_POD}" || failed=1

run_case "run_as_root_container" "fail" "${POD_PATH}" "${RUN_AS_ROOT_CONTAINER_POD}" || failed=1
run_case "run_as_root_pod_level" "fail" "${POD_PATH}" "${RUN_AS_ROOT_POD_LEVEL}" || failed=1
run_case "run_as_root_allowlisted" "pass" "${POD_PATH}" "${RUN_AS_ROOT_POD_LEVEL}" \
  "runAsUser\t${POD_REGEX}\t<pod>\t2999-12-31\tnodejs tooling needs to write root-owned paths" || failed=1

run_case "hostpath_volume" "fail" "${POD_PATH}" "${HOSTPATH_POD}" || failed=1
run_case "hostpath_volume_allowlisted" "pass" "${POD_PATH}" "${HOSTPATH_POD}" \
  "hostPath\t${POD_REGEX}\tmodules\t2999-12-31\tkind needs /lib/modules" || failed=1

run_case "prow_privileged" "fail" "${PROW_PATH}" "${PROW_PRIVILEGED}" || failed=1
run_case "prow_privileged_with_automount_only" "fail" "${PROW_PATH}" "${PROW_PRIVILEGED_AUTOMOUNT}" || failed=1
run_case "prow_plain" "pass" "${PROW_PATH}" "${PROW_PLAIN}" || failed=1
run_case "prow_privileged_allowlisted" "pass" "${PROW_PATH}" "${PROW_PRIVILEGED_AUTOMOUNT}" \
  "privileged\t${PROW_REGEX}\tdind-daemon\t2999-12-31\tdind sidecar" || failed=1
run_case "prow_periodic_hostpath" "fail" "${PROW_PATH}" "${PROW_PERIODIC_HOSTPATH}" || failed=1
run_case "prow_periodic_hostpath_allowlisted" "pass" "${PROW_PATH}" "${PROW_PERIODIC_HOSTPATH}" \
  "hostPath\t${PROW_REGEX}\tcgroup\t2999-12-31\tkind needs cgroup" || failed=1

rm -f /tmp/privilege_policy_test.out

if [[ "${failed}" -ne 0 ]]; then
  echo "pod privilege policy tests FAILED" >&2
  exit 1
fi

echo "pod privilege policy tests passed"
