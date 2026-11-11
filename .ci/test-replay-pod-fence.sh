#!/usr/bin/env bash
# Tests for the pod-policy fence in .ci/replay-jenkins-build.sh.
#
# The replay hands a PR-authored Pod template to Jenkins with CI credentials, so
# the fence must validate the template Jenkins will actually read. It used to
# validate only the `pod*.yaml` files beside the Jenkinsfile, which says nothing
# about an agent pod spec the Jenkinsfile declares itself or points at from
# another job folder.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
replay="$repo_root/.ci/replay-jenkins-build.sh"

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
cd "$fixture"

# Image resolution is a different gate (and needs a registry); keep the fence
# tests offline.
# These tests run the replay locally, without a base revision; allow the
# checkout copies of the pod policy explicitly (CI always has a base).
export CI_ALLOW_UNFENCED_POD_POLICY=1
export CRANE_BIN="crane-not-installed-for-this-test"
export JENKINS_MASTER_0_URL="https://jenkins.example/0"
export JENKINS_MASTER_1_URL="https://jenkins.example/1"

failures=0
pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1" >&2; failures=$((failures + 1)); }

job_dir() { printf 'jenkins/jobs/%s/latest/%s' "$1" "$2"; }

unprivileged_pod() {
    cat <<'YAML'
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: runner
      image: ghcr.io/pingcap-qe/ci/jenkins:v2026.3.22-4-gd9d1135
      securityContext:
        privileged: false
YAML
}

privileged_pod() {
    cat <<'YAML'
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: runner
      image: ghcr.io/pingcap-qe/ci/jenkins:v2026.3.22-4-gd9d1135
      securityContext:
        privileged: true
YAML
}

# run_replay <jenkinsfile> -> exit status, output in $tmp/output
run_replay() {
    local script_file="$1"
    set +e
    "$replay" --script-file "$script_file" --job-url "https://jenkins.example/0/job/example/job/repo/job/test" \
        --dry-run --no-inline-pod-yaml >"$fixture/output" 2>&1
    local rc=$?
    set -e
    return "$rc"
}

expect_allowed() {
    local name="$1" script_file="$2"
    if run_replay "$script_file"; then
        pass "$name"
    else
        fail "$name (refused: $(tail -2 "$fixture/output" | head -1))"
    fi
}

expect_refused() {
    local name="$1" script_file="$2" needle="$3"
    if run_replay "$script_file"; then
        fail "$name (allowed, expected refusal)"
    elif ! grep -q "$needle" "$fixture/output"; then
        fail "$name (refused for the wrong reason: $(tail -2 "$fixture/output" | head -1))"
    else
        pass "$name"
    fi
}

# 1. Sibling template, unprivileged: allowed.
mkdir -p "$(job_dir example/repo plain)"
printf 'pipeline { agent { kubernetes { yamlFile POD_TEMPLATE_FILE } } }\n' \
    > "$(job_dir example/repo plain)/Jenkinsfile"
printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo plain)/pod.yaml" \
    >> "$(job_dir example/repo plain)/Jenkinsfile"
unprivileged_pod > "$(job_dir example/repo plain)/pod.yaml"
expect_allowed "sibling pod template is checked and allowed" "$(job_dir example/repo plain)/Jenkinsfile"

# 2. A literal agent pod spec in the Jenkinsfile cannot be validated.
mkdir -p "$(job_dir example/repo inline)"
{
    printf 'pipeline { agent { kubernetes {\n'
    printf 'yaml """apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: runner\n      image: x\n"""\n'
    printf '} } }\n'
} > "$(job_dir example/repo inline)/Jenkinsfile"
expect_refused "an inline literal agent pod spec is refused" \
    "$(job_dir example/repo inline)/Jenkinsfile" "cannot validate"

# 3. A template in another job folder is validated too, so a privileged one fails.
mkdir -p "$(job_dir example/repo foreign)" "$(job_dir example/other target)"
privileged_pod > "$(job_dir example/other target)/pod.yaml"
{
    printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/other target)/pod.yaml"
    printf 'pipeline { agent { kubernetes { yamlFile POD_TEMPLATE_FILE } } }\n'
} > "$(job_dir example/repo foreign)/Jenkinsfile"
expect_refused "a template referenced from another job folder is validated" \
    "$(job_dir example/repo foreign)/Jenkinsfile" "refusing to replay"

# 4. An undeclared template variable cannot be resolved.
mkdir -p "$(job_dir example/repo undeclared)"
printf 'pipeline { agent { kubernetes { yamlFile POD_TEMPLATE_FILE } } }\n' \
    > "$(job_dir example/repo undeclared)/Jenkinsfile"
expect_refused "an undeclared template variable is refused" \
    "$(job_dir example/repo undeclared)/Jenkinsfile" "does not declare it"

# 5. A Jenkinsfile that declares no pod spec at all has nothing to check.
mkdir -p "$(job_dir example/repo budget)"
printf 'pipeline { agent { kubernetes {} } }\n' > "$(job_dir example/repo budget)/Jenkinsfile"
expect_allowed "an agent without a pod spec is allowed" "$(job_dir example/repo budget)/Jenkinsfile"

# 6. The ci-label form resolves through its declaration as well.
mkdir -p "$(job_dir example/repo labels)"
unprivileged_pod > "$(job_dir example/repo labels)/pod.yaml"
{
    printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo labels)/pod.yaml"
    printf 'pipeline { agent { kubernetes { yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS) } } }\n'
} > "$(job_dir example/repo labels)/Jenkinsfile"
expect_allowed "the withCiLabels form is resolved and allowed" "$(job_dir example/repo labels)/Jenkinsfile"

if [ "$failures" -ne 0 ]; then
    echo "replay pod fence tests failed: $failures" >&2
    exit 1
fi
echo "replay pod fence tests passed."
