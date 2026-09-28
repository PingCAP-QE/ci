#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"

mkdir -p prow-jobs/example/repo jenkins/jobs/example/repo/latest/{old,new}
printf 'pipeline {}\n' > jenkins/jobs/example/repo/latest/old/Jenkinsfile
printf 'pipeline {}\n' > jenkins/jobs/example/repo/latest/new/Jenkinsfile
cat > prow-jobs/example/repo/presubmits.yaml <<'YAML'
presubmits:
  example/repo:
    - name: example/repo/old
      agent: jenkins
      labels:
        master: "1"
    - name: example/repo/new
      agent: jenkins
      labels:
        master: "0"
YAML

export JENKINS_MASTER_0_URL=https://new.example/jenkins
export JENKINS_MASTER_1_URL=https://old.example/jenkins

replay_dry_run() {
    "$repo_root/.ci/replay-jenkins-build.sh" \
        --script-file "jenkins/jobs/example/repo/latest/$1/Jenkinsfile" \
        --route-by-prow-master --dry-run --no-inline-pod-yaml 2>&1
}

old_output="$(replay_dry_run old)"
[[ "$old_output" == *'master=1 -> https://old.example/jenkins'* ]]
[[ "$old_output" == *'https://old.example/jenkins/job/example/job/repo/job/old/lastSuccessfulBuild'* ]]

new_output="$(replay_dry_run new)"
[[ "$new_output" == *'master=0 -> https://new.example/jenkins'* ]]
[[ "$new_output" == *'https://new.example/jenkins/job/example/job/repo/job/new/lastSuccessfulBuild'* ]]

cat > prow-jobs/example/repo/conflict.yaml <<'YAML'
postsubmits:
  example/repo:
    - name: example/repo/new
      agent: jenkins
      labels:
        master: "1"
YAML
if replay_dry_run new > output.log 2>&1; then
    echo 'expected conflicting master labels to fail' >&2
    exit 1
fi
rg -q 'conflicting Prow labels.master' output.log

echo 'Jenkins replay routing tests passed.'
