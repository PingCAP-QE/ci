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

# 4b. The parenthesised call form must resolve to the same checked template.
mkdir -p "$(job_dir example/repo parens)"
unprivileged_pod > "$(job_dir example/repo parens)/pod.yaml"
{
    printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo parens)/pod.yaml"
    printf 'pipeline { agent { kubernetes { yamlFile(POD_TEMPLATE_FILE) } } }\n'
} > "$(job_dir example/repo parens)/Jenkinsfile"
expect_allowed "the yamlFile(...) form is resolved and allowed" "$(job_dir example/repo parens)/Jenkinsfile"

# 4c. A parenthesised literal cannot be validated.
mkdir -p "$(job_dir example/repo parens-literal)"
printf 'pipeline { agent { kubernetes { yaml("""kind: Pod""") } } }\n' \
    > "$(job_dir example/repo parens-literal)/Jenkinsfile"
expect_refused "a yaml(...) literal agent pod spec is refused" \
    "$(job_dir example/repo parens-literal)/Jenkinsfile" "cannot validate"

# 4d. A computed argument cannot be validated.
mkdir -p "$(job_dir example/repo computed)"
printf 'pipeline { agent { kubernetes { yaml(POD_SPEC) } } }\n' \
    > "$(job_dir example/repo computed)/Jenkinsfile"
expect_refused "a computed yaml(POD_SPEC) argument is refused" \
    "$(job_dir example/repo computed)/Jenkinsfile" "cannot validate"

# 4e. A template the job folder names something other than pod*.yaml is still
#     resolved and checked.
mkdir -p "$(job_dir example/repo custom-name)"
unprivileged_pod > "$(job_dir example/repo custom-name)/agent-template.yaml"
{
    printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo custom-name)/agent-template.yaml"
    printf 'pipeline { agent { kubernetes { yamlFile POD_TEMPLATE_FILE } } }\n'
} > "$(job_dir example/repo custom-name)/Jenkinsfile"
expect_allowed "a template namespaced by another file name is checked" \
    "$(job_dir example/repo custom-name)/Jenkinsfile"

# 4f. A decoy comment must not get a clean sibling validated while the spec
#     Jenkins uses is inline.
mkdir -p "$(job_dir example/repo decoy)"
unprivileged_pod > "$(job_dir example/repo decoy)/pod.yaml"
{
    printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo decoy)/pod.yaml"
    printf 'pipeline { agent { kubernetes { yaml EVIL_SPEC } } } // yamlFile POD_TEMPLATE_FILE\n'
} > "$(job_dir example/repo decoy)/Jenkinsfile"
expect_refused "a decoy yamlFile comment is refused" \
    "$(job_dir example/repo decoy)/Jenkinsfile" "declared more than once"

# 4g. The named-argument form cannot be validated either.
mkdir -p "$(job_dir example/repo named-arg)"
printf 'pipeline { agent { podTemplate(yaml: """kind: Pod""") } }\n' \
    > "$(job_dir example/repo named-arg)/Jenkinsfile"
expect_refused "the podTemplate(yaml: ...) form is refused" \
    "$(job_dir example/repo named-arg)/Jenkinsfile" "cannot validate"

# 4h. Groovy accepts a string literal directly after a command expression, so an
#     inline spec can follow `yaml` with no separator at all.
for form in "yaml'''apiVersion: v1" 'yaml"""apiVersion: v1' "yaml/*c*/'''apiVersion: v1"; do
    slug="unquoted-$(printf '%s' "$form" | tr -cd 'a-z')"
    mkdir -p "$(job_dir example/repo "$slug")"
    unprivileged_pod > "$(job_dir example/repo "$slug")/pod.yaml"
    {
        printf "final POD_TEMPLATE_FILE = '%s'\n" "$(job_dir example/repo "$slug")/pod.yaml"
        printf 'pipeline { agent { kubernetes { %s\n' "$form"
    } > "$(job_dir example/repo "$slug")/Jenkinsfile"
    expect_refused "an inline spec written as '${form%%apiVersion*}' is refused" \
        "$(job_dir example/repo "$slug")/Jenkinsfile" "cannot validate"
done

# 4i. The Kubernetes plugin DSL builds the pod spec without any yaml token, so
#     the fence used to find nothing to check, pass on the clean sibling
#     template, and let the replay schedule the DSL-built pod.
mkdir -p "$(job_dir example/repo podtemplate)"
unprivileged_pod > "$(job_dir example/repo podtemplate)/pod.yaml"
cat > "$(job_dir example/repo podtemplate)/Jenkinsfile" <<'JENKINSFILE'
def POD_LABEL = podTemplate(containers: [containerTemplate(name: 'runner', image: 'example/evil:latest', privileged: true)],
                            volumes: [hostPathVolume(hostPath: '/', mountPath: '/host')],
                            serviceAccount: 'gar-reader') { node(POD_LABEL) { sh 'whoami' } }
JENKINSFILE
expect_refused "a pod spec built with podTemplate(...) is refused even next to a clean sibling" \
    "$(job_dir example/repo podtemplate)/Jenkinsfile" "cannot validate"

# 4j. The same DSL with no sibling template: the policy used to run on nothing.
mkdir -p "$(job_dir example/repo podtemplate-bare)"
cat > "$(job_dir example/repo podtemplate-bare)/Jenkinsfile" <<'JENKINSFILE'
def spec = this.podTemplate(containers: [containerTemplate(name: 'runner', image: 'example/evil:latest', privileged: true)])
spec.labels()
JENKINSFILE
expect_refused "a pod spec built with this.podTemplate(...) is refused" \
    "$(job_dir example/repo podtemplate-bare)/Jenkinsfile" "cannot validate"

# 4k. containerTemplate is refused on its own as well, so splitting the spec
#     across lines does not help.
mkdir -p "$(job_dir example/repo containertemplate)"
printf 'def runner = containerTemplate(name: %s, image: %s, privileged: true)\n' \
    "'runner'" "'example/evil:latest'" \
    > "$(job_dir example/repo containertemplate)/Jenkinsfile"
expect_refused "a containerTemplate(...) declaration is refused" \
    "$(job_dir example/repo containertemplate)/Jenkinsfile" "cannot validate"

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
