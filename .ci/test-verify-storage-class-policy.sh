#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="${ROOT_DIR}/.ci/verify-storage-class-policy.sh"

if ! command -v git >/dev/null 2>&1; then
  echo "git is required" >&2
  exit 1
fi

# The gate scans these trees; each must exist for the scan to be meaningful.
SCAN_ROOTS=(jenkins libraries prow-jobs tekton)

# Table: name | expected result (pass/fail) | changed file | payload |
#        allowlist body | env override ("permissive") | scan root to remove
run_case() {
  local name="$1"
  local expect="$2"
  local head_file="$3"
  local payload="$4"
  local allowlist="${5:-ci-rwo}"
  local env_override="${6:-}"
  local remove_root="${7:-}"

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN

  pushd "${tmp}" >/dev/null

  git init -q
  git config user.email "ci-bot@example.com"
  git config user.name "CI Bot"

  local root
  for root in "${SCAN_ROOTS[@]}"; do
    mkdir -p "${root}"
  done

  printf '# placeholder\n' >"jenkins/placeholder.yaml"
  git add -A
  git commit -q -m "seed"

  mkdir -p "$(dirname "${head_file}")"
  printf '%s\n' "${payload}" >"${head_file}"
  git add -A
  git commit -q -m "head"

  # The allowlist is part of the reviewed tree.
  mkdir -p .ci
  printf '%s\n' "${allowlist}" > .ci/storage-class-policy-allowlist.txt

  local override_path=""
  if [[ "${env_override}" == "permissive" ]]; then
    # An allowlist that WOULD permit the violation, outside the reviewed tree.
    override_path="${tmp}/permissive-allowlist.txt"
    printf 'ci-rwo\nhyperdisk-rwo\n' >"${override_path}"
  fi

  if [[ -n "${remove_root}" ]]; then
    rmdir "${remove_root}"
  fi

  local rc=0
  if [[ -n "${override_path}" ]]; then
    env STORAGE_CLASS_POLICY_ALLOWLIST_FILE="${override_path}" \
      bash "${SCRIPT_PATH}" || rc=$?
  else
    bash "${SCRIPT_PATH}" || rc=$?
  fi

  popd >/dev/null

  if [[ "${expect}" == "pass" && "${rc}" -eq 0 ]]; then
    echo "[PASS] ${name}"
    return 0
  fi

  if [[ "${expect}" == "fail" && "${rc}" -ne 0 ]]; then
    echo "[PASS] ${name}"
    return 0
  fi

  echo "[FAIL] ${name} (expect=${expect}, rc=${rc})" >&2
  return 1
}

run_case "allowlisted_storage_class" "pass" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/pod.yaml" \
  'apiVersion: v1
kind: Pod
spec:
  containers:
    - name: main
      image: alpine:3.24'

run_case "direct_ci_rwo_is_allowed" "pass" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/pod.yaml" \
  'apiVersion: v1
kind: PersistentVolumeClaim
spec:
  storageClassName: ci-rwo'

run_case "env_indirection_is_allowed" "pass" \
  "tekton/v1/tasks/workspace.yaml" \
  'apiVersion: tekton.dev/v1
kind: Task
spec:
  workspaces:
    - name: source
      persistentVolumeClaim:
        storageClassName: ${STORAGE_CLASSNAME_RWO}'

run_case "non_allowlisted_storage_class_fails" "fail" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/pod.yaml" \
  'apiVersion: v1
kind: PersistentVolumeClaim
spec:
  storageClassName: hyperdisk-rwo'

run_case "quoted_non_allowlisted_storage_class_fails" "fail" \
  "prow-jobs/pingcap-qe/ci/presubmits.yaml" \
  'presubmits:
  PingCAP-QE/ci:
    - name: test
      spec:
        volumes:
          - name: workspace
            persistentVolumeClaim:
              storageClassName: "standard-rwo"
        containers:
          - name: main
            image: alpine:3.24'

# The allowlist location is not redirectable through the environment: pointing
# the gate at a permissive file must not exempt a real violation.
run_case "allowlist_path_env_override_ignored" "fail" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/pod.yaml" \
  'apiVersion: v1
kind: PersistentVolumeClaim
spec:
  storageClassName: hyperdisk-rwo' \
  'ci-rwo' \
  'permissive'

# A renamed/removed tree must fail loudly instead of silently scanning less.
run_case "missing_scan_path_fails" "fail" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/pod.yaml" \
  'apiVersion: v1
kind: PersistentVolumeClaim
spec:
  storageClassName: ci-rwo' \
  'ci-rwo' \
  '' \
  'tekton'

echo "All storage-class-policy regression tests passed."
