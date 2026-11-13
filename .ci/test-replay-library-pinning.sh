#!/usr/bin/env bash
# Tests for the library-pinning mode of .ci/replay-jenkins-build.sh.
#
# A shared-library change must not produce a green replay of unchanged code: the
# replay replaces the pipeline script only, so the library has to be pinned to
# the head revision and the run has to confirm it loaded that pin. These tests
# exercise the selection, the annotation rewrite and the fail-closed paths, all
# with --dry-run so no Jenkins is needed.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"

failures=0
fail() {
    printf 'FAIL: %s\n' "$*" >&2
    failures=$((failures + 1))
}
pass() {
    printf 'ok: %s\n' "$*"
}

job_dir=jenkins/jobs/example/repo/latest/build
mkdir -p "$job_dir" libraries/tipipeline/vars .ci
cat > "$job_dir/Jenkinsfile" <<'JENKINSFILE'
@Library('tipipeline') _

pipeline {
    agent any
    stages {
        stage('build') { steps { sh 'true' } }
    }
}
JENKINSFILE
printf 'def checkoutRefs() {}\n' > libraries/tipipeline/vars/prow.groovy
cp "$repo_root/.ci/replay-library-jobs.txt" .ci/replay-library-jobs.txt
printf '%s\n' "$job_dir/Jenkinsfile" > .ci/replay-library-jobs.txt

git init -q .
git config user.email test@example.com
git config user.name test
git add -A
git commit -qm 'base'
base_sha="$(git rev-parse HEAD)"

# A library-only change: no Jenkinsfile is touched.
printf 'def checkoutRefs() { println "changed" }\n' > libraries/tipipeline/vars/prow.groovy
git add -A
git commit -qm 'change the shared library'
head_sha="$(git rev-parse HEAD)"

run_replay() {
    CI_ALLOW_UNFENCED_POD_POLICY=1 \
        bash "$repo_root/.ci/replay-jenkins-build.sh" \
        --auto-changed \
        --base-sha "$base_sha" \
        --head-sha "$head_sha" \
        --dry-run --verbose "$@"
}

# 1. A library-only change selects the listed job and pins the library.
out="$(run_replay --dump-script dumped.groovy 2>&1)" && rc=0 || rc=$?
if [[ "$rc" != "0" ]]; then
    fail "library-only change should replay the listed jobs (rc=${rc}):\n${out}"
elif [[ "$out" != *"shared library changed (tipipeline=${head_sha})"* ]]; then
    fail "the pins found in the diff were not reported:\n${out}"
elif [[ "$out" != *"${job_dir}/Jenkinsfile"* ]]; then
    fail "the job listed in .ci/replay-library-jobs.txt was not selected:\n${out}"
elif ! grep -q "@Library('tipipeline@${head_sha}')" dumped.groovy; then
    fail "the dumped script does not pin the library: $(cat dumped.groovy)"
elif grep -q "@Library('tipipeline')" dumped.groovy; then
    fail "the dumped script still has an unpinned annotation: $(cat dumped.groovy)"
else
    pass "library-only change selects the listed job and pins the library"
fi

# 2. A Jenkinsfile change alone must not pin anything.
printf '@Library(%s) _\n\npipeline { agent any; stages { stage(%s) { steps { sh %s } } } }\n' \
    "'tipipeline'" "'noop'" "'true'" > "$job_dir/Jenkinsfile"
git add -A
git commit -qm 'touch the job'
job_head_sha="$(git rev-parse HEAD)"
out="$(CI_ALLOW_UNFENCED_POD_POLICY=1 bash "$repo_root/.ci/replay-jenkins-build.sh" \
    --auto-changed --base-sha "$job_head_sha~1" --head-sha "$job_head_sha" \
    --dry-run --verbose --dump-script dumped-job.groovy 2>&1)" && rc=0 || rc=$?
if [[ "$rc" != "0" ]]; then
    fail "a Jenkinsfile change should still replay (rc=${rc}):\n${out}"
elif [[ "$out" == *"shared library changed"* ]]; then
    fail "a Jenkinsfile change must not report a library pin:\n${out}"
elif ! grep -q "@Library('tipipeline')" dumped-job.groovy; then
    fail "a Jenkinsfile-only replay must keep the annotation untouched: $(cat dumped-job.groovy)"
else
    pass "a Jenkinsfile change replays without pinning the library"
fi

# 3. No job list for a library change is fatal, not an empty green run.
mv .ci/replay-library-jobs.txt .ci/replay-library-jobs.txt.off
out="$(run_replay 2>&1)" && rc=0 || rc=$?
mv .ci/replay-library-jobs.txt.off .ci/replay-library-jobs.txt
if [[ "$rc" == "0" ]]; then
    fail "a library change without .ci/replay-library-jobs.txt must fail, not replay nothing:\n${out}"
elif [[ "$out" != *"is missing"* ]]; then
    fail "the failure must say the job list is missing:\n${out}"
else
    pass "a library change without a job list fails closed"
fi

# 4. A listed job that does not load the pinned library is fatal.
printf 'pipeline { agent any; stages { stage(%s) { steps { sh %s } } } }\n' "'noop'" "'true'" \
    > "$job_dir/Jenkinsfile"
printf 'def checkoutRefs() { println "changed again" }\n' > libraries/tipipeline/vars/prow.groovy
git add -A
git commit -qm 'drop the library annotation and change the library'
no_library_sha="$(git rev-parse HEAD)"
out="$(CI_ALLOW_UNFENCED_POD_POLICY=1 bash "$repo_root/.ci/replay-jenkins-build.sh" \
    --auto-changed --base-sha "$job_head_sha" --head-sha "$no_library_sha" \
    --dry-run --verbose 2>&1)" && rc=0 || rc=$?
if [[ "$rc" == "0" ]]; then
    fail "a job that does not load the changed library must fail the replay:\n${out}"
elif [[ "$out" != *"does not load library"* ]]; then
    fail "the failure must name the missing library annotation:\n${out}"
else
    pass "a job that cannot exercise the change is refused"
fi

# 5. An explicit pin without --wait cannot confirm it took effect. This fails in
#    argument validation, so it never reaches the network.
out="$(CI_ALLOW_UNFENCED_POD_POLICY=1 bash "$repo_root/.ci/replay-jenkins-build.sh" \
    --script-file "$job_dir/Jenkinsfile" --build-url https://jenkins.example/job/x/1 \
    --library-version "tipipeline=deadbeef" 2>&1)" && rc=0 || rc=$?
if [[ "$rc" == "0" ]]; then
    fail "a pinned replay without --wait must be refused:\n${out}"
elif [[ "$out" != *"requires --wait"* ]]; then
    fail "the refusal must explain that --wait is required:\n${out}"
else
    pass "a pinned replay without --wait is refused"
fi

# 6. A malformed pin is refused.
out="$(CI_ALLOW_UNFENCED_POD_POLICY=1 bash "$repo_root/.ci/replay-jenkins-build.sh" \
    --script-file "$job_dir/Jenkinsfile" --build-url https://jenkins.example/job/x/1 \
    --library-version "tipipeline" --wait --dry-run 2>&1)" && rc=0 || rc=$?
if [[ "$rc" == "0" ]]; then
    fail "a pin without a revision must be refused:\n${out}"
elif [[ "$out" != *"expects <name=ref>"* ]]; then
    fail "the refusal must show the expected form:\n${out}"
else
    pass "a malformed pin is refused"
fi

# 7. Every annotation form in the repository is pinned, unchanged otherwise.
#    The four supported spellings are pinned; a multi-library list is refused
#    instead of being replayed unpinned. Each case commits the annotation and a
#    library change together, because the pin comes from the diff.
case_number=0
check_annotation() {
    local annotation="$1" expected="$2" wanted="$3"
    case_number=$((case_number + 1))
    printf '%s\n\npipeline { agent any; stages { stage(%s) { steps { sh %s } } } }\n' \
        "$annotation" "'noop'" "'true'" > "$job_dir/Jenkinsfile"
    printf 'def checkoutRefs() { println "case %s" }\n' "$case_number" \
        > libraries/tipipeline/vars/prow.groovy
    git add -A
    git commit -qm "annotation case: ${annotation}"
    local case_sha
    case_sha="$(git rev-parse HEAD)"
    local out rc
    out="$(CI_ALLOW_UNFENCED_POD_POLICY=1 bash "$repo_root/.ci/replay-jenkins-build.sh" \
        --auto-changed --base-sha "${case_sha}~1" --head-sha "$case_sha" \
        --dry-run --verbose --dump-script "case.groovy" 2>&1)" && rc=0 || rc=$?
    if [[ "$wanted" == "pinned" ]]; then
        local want="${expected//@REF/@${case_sha}}"
        if [[ "$rc" != "0" ]]; then
            fail "[${annotation}] should replay: ${out}"
        elif ! grep -qF "$want" case.groovy; then
            fail "[${annotation}] expected ${want} in the replay script: $(head -1 case.groovy)"
        else
            pass "[${annotation}] is pinned"
        fi
    else
        if [[ "$rc" == "0" ]]; then
            fail "[${annotation}] must be refused rather than replayed unpinned"
        elif [[ "$out" != *"multi-library @Library"* ]]; then
            fail "[${annotation}] the refusal must name the unsupported form: ${out}"
        else
            pass "[${annotation}] is refused"
        fi
    fi
}

check_annotation "@Library('tipipeline') _" "@Library('tipipeline@REF')" pinned
check_annotation '@Library("tipipeline") _' "@Library('tipipeline@REF')" pinned
check_annotation "@Library(['tipipeline']) _" "@Library('tipipeline@REF')" pinned
check_annotation "@Library('tipipeline@main') _" "@Library('tipipeline@REF')" pinned
check_annotation "@Library(['tipipeline', 'tisys']) _" "" refused

if (( failures > 0 )); then
    printf '\n%d check(s) failed\n' "$failures" >&2
    exit 1
fi
printf '\nall replay library checks passed\n'
