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

run_case() {
  local name="$1"
  local expect="$2"
  local seed_file="$3"
  local head_file="$4"

  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN

  pushd "${tmp}" >/dev/null

  git init -q
  git config user.email "ci-bot@example.com"
  git config user.name "CI Bot"

  mkdir -p "$(dirname "${seed_file}")"
  cat >"${seed_file}" <<'SEED'
// baseline safe content
SEED

  git add .
  git commit -q -m "seed"
  local base_sha
  base_sha="$(git rev-parse HEAD)"

  mkdir -p "$(dirname "${head_file}")"
  cat >"${head_file}" <<'HEAD'
PLACEHOLDER
HEAD

  # Replace placeholder with actual case-specific payload.
  case "${name}" in
    safe_groovy)
      cat >"${head_file}" <<'PAYLOAD'
def call() {
  withCredentials([string(credentialsId: 'safe-token-id', variable: 'SAFE_TOKEN')]) {
    sh 'echo "run checks"'
  }
}
PAYLOAD
      ;;
    hardcoded_secret_literal)
      cat >"${head_file}" <<'PAYLOAD'
def token = "hardcoded_secret_token"
PAYLOAD
      ;;
    secret_like_env_value_in_prow_yaml)
      cat >"${head_file}" <<'PAYLOAD'
presubmits:
  PingCAP-QE/ci:
    - name: test
      spec:
        containers:
          - env:
              - name: JENKINS_TOKEN
                value: plain-token
PAYLOAD
      ;;
    *)
      echo "unknown case: ${name}" >&2
      exit 1
      ;;
  esac

  git add .
  git commit -q -m "head"
  local head_sha
  head_sha="$(git rev-parse HEAD)"

  set +e
  PULL_BASE_SHA="${base_sha}" PULL_PULL_SHA="${head_sha}" bash "${SCRIPT_PATH}"
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

  echo "[FAIL] ${name} (expect=${expect}, rc=${rc})" >&2
  return 1
}

run_case "safe_groovy" "pass" \
  "pipelines/pingcap/tidb/latest/pull_safe.groovy" \
  "pipelines/pingcap/tidb/latest/pull_safe.groovy"

run_case "hardcoded_secret_literal" "fail" \
  "jobs/pingcap/tidb/latest/pull_check.groovy" \
  "jobs/pingcap/tidb/latest/pull_check.groovy"

run_case "secret_like_env_value_in_prow_yaml" "fail" \
  "prow-jobs/pingcap-qe/ci/presubmits.yaml" \
  "prow-jobs/pingcap-qe/ci/presubmits.yaml"

echo "All credential-policy regression tests passed."
