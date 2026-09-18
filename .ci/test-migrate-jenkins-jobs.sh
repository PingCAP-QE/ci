#!/usr/bin/env bash
# shellcheck disable=SC2016  # literal ${...} is intentional in reference checks
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOL="${ROOT_DIR}/scripts/migrate-jenkins-jobs.sh"
CHECKER="${ROOT_DIR}/.ci/check-jenkins-job-references.sh"
FIXTURE="${ROOT_DIR}/tests/fixtures/jenkins-migration"

if [[ ! -f "${TOOL}" ]]; then
  echo "migration tool not found: ${TOOL}" >&2
  exit 1
fi

failures=0
checks=0

ok() {
  checks=$((checks + 1))
  echo "[PASS] $1"
}

bad() {
  checks=$((checks + 1))
  failures=$((failures + 1))
  echo "[FAIL] $1" >&2
}

assert_file() {
  if [[ -f "$1" ]]; then ok "file exists: ${1#"${TMP_ROOT}"/}"; else bad "file missing: $1"; fi
}

assert_symlink() {
  if [[ -L "$1" && -e "$1" ]]; then ok "symlink resolves: ${1#"${TMP_ROOT}"/}"; else bad "symlink missing/dangling: $1"; fi
}

assert_absent() {
  if [[ ! -e "$1" ]]; then ok "absent: ${1#"${TMP_ROOT}"/}"; else bad "should be absent: $1"; fi
}

assert_contains() {
  if grep -qF "$2" "$1"; then ok "contains '${2}' in ${1#"${TMP_ROOT}"/}"; else bad "missing '${2}' in $1"; fi
}

TMP_ROOT=""

# --- migration happy path ---
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT
cp -R "${FIXTURE}/." "${TMP_ROOT}/"

DRY_OUT="$(bash "${TOOL}" --root "${TMP_ROOT}" --dry-run)"
if grep -qF "jenkins/jobs/acme/widget/latest/build/Jenkinsfile" <<<"${DRY_OUT}"; then
  ok "dry-run plans the Jenkinsfile move"
else
  bad "dry-run did not plan the Jenkinsfile move"
fi
if [[ ! -e "${TMP_ROOT}/jenkins/jobs" && ! -L "${TMP_ROOT}/jobs/acme/widget/latest/build.groovy" ]]; then
  ok "dry-run changed nothing"
else
  bad "dry-run modified the tree"
fi

bash "${TOOL}" --root "${TMP_ROOT}" --apply >/dev/null

assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/dsl.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/dsl.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/pod-build.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/pod-test.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aa_folder.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/refs/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/dsl.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/run.sh"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/common/helper.sh"

assert_symlink "${TMP_ROOT}/jobs/acme/widget/latest/build.groovy"
assert_symlink "${TMP_ROOT}/pipelines/acme/widget/latest/build.groovy"
assert_symlink "${TMP_ROOT}/pipelines/acme/widget/latest/pod-build.yaml"
assert_symlink "${TMP_ROOT}/pipelines/acme/widget/latest/multi/pipeline.groovy"
assert_symlink "${TMP_ROOT}/pipelines/acme/widget/latest/multi/pod-build.yaml"

assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/dsl.groovy" 'scriptPath("jenkins/jobs/acme/widget/latest/build/Jenkinsfile")'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/build/pod.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/Jenkinsfile" 'jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-build.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/Jenkinsfile" 'jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-test.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/refs/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/refs/pod.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/common/helper.sh'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aux/Jenkinsfile" 'final SELF_DIR = "jenkins/jobs/acme/widget/latest/aux"'

if bash "${CHECKER}" --root "${TMP_ROOT}" --quiet; then
  ok "reference checker passes on the migrated tree"
else
  bad "reference checker failed on the migrated tree"
fi

SECOND_OUT="$(bash "${TOOL}" --root "${TMP_ROOT}" --apply)"
if grep -q "UP-TO-DATE" <<<"${SECOND_OUT}"; then
  ok "re-running --apply is idempotent"
else
  bad "re-running --apply was not recognised as up-to-date"
fi

# --- cleanup guard + success ---
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT
cp -R "${FIXTURE}/." "${TMP_ROOT}/"
bash "${TOOL}" --root "${TMP_ROOT}" --apply >/dev/null

BACKUP="$(mktemp)"
cp "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile" "${BACKUP}"
rm "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"

set +e
bash "${TOOL}" --root "${TMP_ROOT}" --cleanup >/dev/null 2>&1
guard_rc=$?
set -e
if [[ "${guard_rc}" -ne 0 ]]; then
  ok "cleanup refuses when references are not clean"
else
  bad "cleanup ran despite a dangling reference"
fi

cp "${BACKUP}" "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"
rm -f "${BACKUP}"

if bash "${TOOL}" --root "${TMP_ROOT}" --cleanup >/dev/null; then
  ok "cleanup succeeds when references are clean"
else
  bad "cleanup failed on a clean tree"
fi

assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/build.groovy"
if [[ ! -d "${TMP_ROOT}/pipelines" ]]; then ok "pipelines/ tree removed"; else bad "pipelines/ tree still exists"; fi
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"

if [[ "${failures}" -ne 0 ]]; then
  echo "${failures}/${checks} migration test check(s) failed." >&2
  exit 1
fi
echo "All ${checks} Jenkins job migration tests passed."
