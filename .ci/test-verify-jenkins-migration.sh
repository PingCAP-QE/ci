#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"
git init -q
git config user.email test@example.com
git config user.name Test
mkdir -p prow-jobs/example/repo jenkins/jobs/example/repo/latest/build jenkins/jobs/example/repo/release-8.5/renamed
cat > prow-jobs/example/repo/presubmits.yaml <<'YAML'
presubmits:
  example/repo:
    - name: example/repo/build
      agent: jenkins
      labels:
        master: "1"
    - name: example/repo/release-8.5/actual
      agent: jenkins
      labels:
        master: "1"
    - name: example/repo/already_migrated
      agent: jenkins
      labels:
        master: "0"
YAML
git add .
git commit -qm base
base_sha="$(git rev-parse HEAD)"
sed -i.bak 's/master: "1"/master: "0"/g' prow-jobs/example/repo/presubmits.yaml
rm prow-jobs/example/repo/presubmits.yaml.bak
git add .
git commit -qm head
head_sha="$(git rev-parse HEAD)"
# Leave a different worktree value to prove detection uses the supplied head.
sed -i.bak 's/master: "0"/master: "1"/g' prow-jobs/example/repo/presubmits.yaml
rm prow-jobs/example/repo/presubmits.yaml.bak
printf "pipelineJob('example/repo/build') {}\n" > jenkins/jobs/example/repo/latest/build/dsl.groovy
printf 'pipeline {}\n' > jenkins/jobs/example/repo/latest/build/Jenkinsfile
printf "pipelineJob('example/repo/release-8.5/actual') {}\n" > jenkins/jobs/example/repo/release-8.5/renamed/dsl.groovy
printf 'pipeline {}\n' > jenkins/jobs/example/repo/release-8.5/renamed/Jenkinsfile

source "$repo_root/.ci/verify-jenkins-migration.sh"
BASE_SHA="$base_sha"
HEAD_SHA="$head_sha"
actual="$(collect_flipped_jobs "$BASE_SHA" "$HEAD_SHA" | LC_ALL=C sort)"
expected=$(cat <<'EXPECTED'
example/repo/build	prow-jobs/example/repo/presubmits.yaml
example/repo/release-8.5/actual	prow-jobs/example/repo/presubmits.yaml
EXPECTED
)
if [[ "$actual" != "$expected" ]]; then
    echo 'unexpected flipped jobs:' >&2
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
    exit 1
fi
# A job that was already at master "0" on the base commit is not a migration.
if grep -q '^example/repo/already_migrated' <<<"$actual"; then
    echo 'job already at master "0" on base must not be reported' >&2
    exit 1
fi
[[ "$(job_name_to_dsl_file example/repo/build)" == 'jenkins/jobs/example/repo/latest/build/dsl.groovy' ]]
[[ "$(job_name_to_dsl_file example/repo/release-8.5/actual)" == 'jenkins/jobs/example/repo/release-8.5/renamed/dsl.groovy' ]]
[[ "$(job_name_to_path example/repo/build)" == 'job/example/job/repo/job/build' ]]
if job_name_to_dsl_file example/repo/missing >/dev/null; then
    echo 'expected missing DSL lookup to fail' >&2
    exit 1
fi
echo 'Jenkins migration verification tests passed.'
