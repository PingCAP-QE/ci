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

assert_absent() {
  if [[ ! -e "$1" ]]; then ok "absent: ${1#"${TMP_ROOT}"/}"; else bad "should be absent: $1"; fi
}

assert_contains() {
  if grep -qF "$2" "$1"; then ok "contains '${2}' in ${1#"${TMP_ROOT}"/}"; else bad "missing '${2}' in $1"; fi
}

# The migration must not leave any back-compat symlink behind.
assert_no_symlinks() {
  local found
  found="$(find "$1" -type l -print -quit)"
  if [[ -z "${found}" ]]; then ok "no symlinks under ${1#"${TMP_ROOT}"/}"; else bad "unexpected symlink: ${found}"; fi
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
if [[ ! -e "${TMP_ROOT}/jenkins/jobs" && -f "${TMP_ROOT}/jobs/acme/widget/latest/build.groovy" ]]; then
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
# Multi-pod legacy names that repeat the job/repo are collapsed to pod-<purpose>.
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/pod-build.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/pod-test.yaml"
assert_absent "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/pod-bloated-build.yaml"
assert_absent "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/pod-bloated-test.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/aa_folder.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/OWNERS"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/OWNERS"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/refs/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/dsl.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/run.sh"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/common/helper.sh"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_a/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_a/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_b/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_b/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/single/pod.yaml"
assert_absent "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/single/pod-custom.yaml"

# A job without a pod template must migrate too (regression guard: an empty
# pod-constant set used to abort the run and leave a half-migrated job).
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/nopod/dsl.groovy"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/nopod/Jenkinsfile"
assert_absent "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/nopod/pod.yaml"
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/nopod/Jenkinsfile" 'pipeline { agent { kubernetes {} } }'

# The legacy jobs/ tree is emptied by the migration (the DSL is moved) and no
# back-compat symlinks are created.
assert_absent "${TMP_ROOT}/jobs/acme/widget/latest/build.groovy"
assert_absent "${TMP_ROOT}/jobs/acme/widget/latest/nopod.groovy"
assert_no_symlinks "${TMP_ROOT}"

# The migration moves the pipeline/pod into the job folder, so migrated jobs
# leave no legacy duplicate behind.
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/build.groovy"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/pod-build.yaml"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/multi/pipeline.groovy"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/nopod.groovy"

# A pipeline shared by two jobs is copied for the first job and moved for the
# last one, so each job gets its own copy and the legacy source ends up gone.
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_a/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_b/pod.yaml"
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_a/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/shared_a/pod.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_b/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/shared_b/pod.yaml'
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/shared/pipeline.groovy"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/shared/pod.yaml"

# A pipeline shared through a templated scriptPath (two jobs resolving to the
# same target, as with a `<repo>/latest` pipeline referenced from a `dedicated`
# job) must also be copied for the first job and moved for the last one.
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_tmpl/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_tmpl/pod.yaml"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/dedicated/shared_tmpl/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/dedicated/shared_tmpl/pod.yaml"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/shared_tmpl/pipeline.groovy"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/shared_tmpl/pod.yaml"

assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/dsl.groovy" 'scriptPath(ciGroovyPath)'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/dsl.groovy" 'final ciGroovyPath = "jenkins/jobs/${folder}/${jobName}/Jenkinsfile"'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_tmpl/dsl.groovy" 'final ciGroovyPath = "jenkins/jobs/${fullRepo}/latest/${jobName}/Jenkinsfile"'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/dsl.groovy" 'final ciGroovyPath = "jenkins/jobs/acme/widget/latest/multi/Jenkinsfile"'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/build/pod.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/Jenkinsfile" 'jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-build.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/multi/Jenkinsfile" 'jenkins/jobs/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-test.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/bloated/pod-build.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/bloated/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/bloated/pod-test.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/refs/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/refs/pod.yaml'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/Jenkinsfile" 'jenkins/jobs/acme/widget/latest/common/helper.sh'
assert_contains "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/auxiliary/Jenkinsfile" 'final SELF_DIR = "jenkins/jobs/acme/widget/latest/auxiliary"'

if bash "${CHECKER}" --root "${TMP_ROOT}" --quiet; then
  ok "reference checker passes on the migrated tree"
else
  bad "reference checker failed on the migrated tree"
fi

SECOND_OUT="$(bash "${TOOL}" --root "${TMP_ROOT}" --apply)"
if grep -qF "0 job(s) migrated" <<<"${SECOND_OUT}"; then
  ok "re-running --apply is a no-op"
else
  bad "re-running --apply migrated jobs again"
fi
assert_no_symlinks "${TMP_ROOT}"

# --- chunked apply: a source shared across two `--only <branch>` slices ---
# Two jobs in different branches (latest, dedicated) can share one pipeline; the
# migration must still copy for the first chunk and move for the last, because
# the sharing pre-pass is scoped to the repository, not the chunk.
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT
cp -R "${FIXTURE}/." "${TMP_ROOT}/"
bash "${TOOL}" --root "${TMP_ROOT}" --apply --only acme/widget/latest >/dev/null
bash "${TOOL}" --root "${TMP_ROOT}" --apply --only acme/widget/dedicated >/dev/null
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/shared_tmpl/Jenkinsfile"
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/dedicated/shared_tmpl/Jenkinsfile"
assert_absent "${TMP_ROOT}/pipelines/acme/widget/latest/shared_tmpl/pipeline.groovy"
if bash "${CHECKER}" --root "${TMP_ROOT}" --quiet; then
  ok "chunked apply keeps references valid"
else
  bad "chunked apply broke references"
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

if [[ ! -d "${TMP_ROOT}/pipelines" ]]; then ok "pipelines/ tree removed"; else bad "pipelines/ tree still exists"; fi
if [[ ! -d "${TMP_ROOT}/jobs" ]]; then ok "jobs/ tree pruned"; else bad "jobs/ tree still exists"; fi
assert_file "${TMP_ROOT}/jenkins/jobs/acme/widget/latest/build/Jenkinsfile"
assert_no_symlinks "${TMP_ROOT}"

if [[ "${failures}" -ne 0 ]]; then
  echo "${failures}/${checks} migration test check(s) failed." >&2
  exit 1
fi
echo "All ${checks} Jenkins job migration tests passed."
