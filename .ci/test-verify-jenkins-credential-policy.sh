#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="${ROOT_DIR}/.ci/verify-jenkins-credential-policy.sh"

if ! command -v rg >/dev/null 2>&1; then
  echo "ripgrep (rg) is required" >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "git is required" >&2
  exit 1
fi

# The policy scans these trees; they must exist for the scan to be meaningful.
SCAN_ROOTS=(.ci dockerfiles jenkins libraries prow-jobs scripts tekton)

# Table: name | expected result (pass/fail) | changed file | payload | allowlist
# body | env override (pass "permissive" to set a permissive allowlist path).
run_case() {
  local name="$1"
  local expect="$2"
  local head_file="$3"
  local payload="$4"
  local allowlist="${5:-}"
  local env_override="${6:-}"

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

  mkdir -p "$(dirname "${head_file}")"
  printf '// baseline safe content\n' >"${head_file}"
  git add -A
  git commit -q -m "seed"
  local base_sha
  base_sha="$(git rev-parse HEAD)"

  mkdir -p "$(dirname "${head_file}")"
  printf '%s\n' "${payload}" >"${head_file}"
  git add -A
  git commit -q -m "head"
  local head_sha
  head_sha="$(git rev-parse HEAD)"

  # The allowlist is part of the reviewed tree; it is written after the commit
  # so it never appears in the diff under test.
  if [[ -n "${allowlist}" ]]; then
    printf '%s\n' "${allowlist}" > .ci/security-policy-allowlist.txt
  fi

  # A permissive allowlist OUTSIDE the reviewed tree: if the policy honoured
  # the environment override, this file would exempt the violation below.
  local override_path=""
  if [[ "${env_override}" == "permissive" ]]; then
    override_path="${tmp}/permissive-allowlist.txt"
    printf 'hardcoded_literal\t^libraries/tipipeline/vars/pull_env_bypass\\.groovy$\n' \
      >"${override_path}"
  fi

  local rc=0
  if [[ -n "${override_path}" ]]; then
    env PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="${head_sha}" \
      CREDENTIAL_POLICY_ALLOWLIST_FILE="${override_path}" \
      bash "${SCRIPT_PATH}" || rc=$?
  else
    env PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="${head_sha}" \
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

run_case "safe_groovy" "pass" \
  "libraries/tipipeline/vars/pull_safe.groovy" \
  'def call() {
  withCredentials([string(credentialsId: '"'"'safe-token-id'"'"', variable: '"'"'SAFE_TOKEN'"'"')]) {
    sh '"'"'echo "run checks"'"'"'
  }
}'

# Credentials also live outside Groovy/YAML: Jenkinsfiles, shell scripts,
# Dockerfiles and Tekton tasks must be covered by the same policy.
run_case "hardcoded_secret_literal_in_jenkinsfile" "fail" \
  "jenkins/jobs/pingcap/tidb/latest/pull_check/Jenkinsfile" \
  'def token = "hardcoded_secret_token"'

run_case "hardcoded_secret_literal_in_script" "fail" \
  "scripts/publish.sh" \
  'GITHUB_TOKEN="hardcoded_secret_token"'

run_case "hardcoded_secret_literal_in_dockerfile" "fail" \
  "dockerfiles/base/Dockerfile" \
  'ENV GITHUB_TOKEN="hardcoded_secret_token"'

run_case "hardcoded_secret_literal_in_tekton" "fail" \
  "tekton/v1/tasks/publish.yaml" \
  'apiVersion: tekton.dev/v1
kind: Task
spec:
  steps:
    - name: publish
      script: |
        GITHUB_TOKEN="hardcoded_secret_token"'

run_case "secret_like_env_value_in_prow_yaml" "fail" \
  "prow-jobs/pingcap-qe/ci/presubmits.yaml" \
  'presubmits:
  PingCAP-QE/ci:
    - name: test
      spec:
        containers:
          - env:
              - name: JENKINS_TOKEN
                value: plain-token'

run_case "allowlisted_secret_echo" "pass" \
  "libraries/tipipeline/vars/pull_allowlisted.groovy" \
  'def call() {
  sh '"'"'echo ${DEBUG_TOKEN}'"'"'
}' \
  'secret_echo	^libraries/tipipeline/vars/pull_allowlisted\.groovy$'

run_case "non_allowlisted_secret_echo" "fail" \
  "libraries/tipipeline/vars/pull_not_allowlisted.groovy" \
  'def call() {
  sh '"'"'echo ${ANY_SECRET}'"'"'
}'

# The allowlist location is not redirectable through the environment: pointing
# the policy at a permissive file must not exempt a real violation.
run_case "allowlist_path_env_override_ignored" "fail" \
  "libraries/tipipeline/vars/pull_env_bypass.groovy" \
  'def token = "hardcoded_secret_token"' \
  '' \
  'permissive'

echo "All credential-policy regression tests passed."
