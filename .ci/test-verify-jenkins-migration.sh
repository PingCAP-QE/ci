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

# A rejected token used to surface as one cryptic "HTTP error" per job because
# setup_auth() swallowed every non-JSON crumbIssuer response. Stub curl to
# reproduce a controller that rejects the credentials and one without CSRF
# protection, and assert setup_auth() tells them apart.
stub_dir="$fixture/curl-stub"
mkdir -p "$stub_dir"
cat > "$stub_dir/curl" <<'STUB'
#!/usr/bin/env bash
url=""; out=""; want=""; prev=""; method="GET"
for a in "$@"; do
    case "$prev" in
        -o) out="$a" ;;
        -w) want="$a" ;;
        -X) method="$a" ;;
    esac
    case "$a" in http*) url="$a" ;; esac
    prev="$a"
done
status="${STUB_STATUS:-200}"
body="${STUB_BODY:-}"
if [[ "$method" == "POST" ]]; then
    status="${STUB_POST_STATUS:-$status}"
fi
if [[ -n "$out" ]]; then printf '%s' "$body" > "$out"; fi
if [[ -n "$want" ]]; then printf '%s' "$status"; fi
exit 0
STUB
chmod +x "$stub_dir/curl"
PATH="$stub_dir:$PATH"

TO_JENKINS_URL='http://to.example/jenkins'
TO_JENKINS_USER='ci'; TO_JENKINS_TOKEN='to-token'

# 401 from the crumbIssuer means the credentials are rejected: fail loudly and
# name the token to refresh (a bare "HTTP error" per job hid this for days).
export STUB_STATUS=401
auth_err="$fixture/auth-rejected.err"
if ( setup_auth to ) >/dev/null 2>"$auth_err"; then
    echo 'expected setup_auth to fail when the to jenkins rejects the credentials' >&2
    exit 1
fi
grep -q 'Refresh TO_JENKINS_TOKEN' "$auth_err" || {
    echo 'setup_auth did not name the token to refresh:' >&2
    cat "$auth_err" >&2
    exit 1
}

# 404 is a controller without CSRF protection: no crumb is fine, keep going.
export STUB_STATUS=404
CURL_HEADERS_TO=()
setup_auth to >/dev/null 2>&1
[[ "${#CURL_HEADERS_TO[@]}" -eq 0 ]] || {
    echo 'expected no crumb header for a controller without a crumb issuer' >&2
    exit 1
}

# A healthy crumb issuer configures the header.
export STUB_STATUS=200
export STUB_BODY='{"crumbRequestField":"Jenkins-Crumb","crumb":"abc"}'
setup_auth to >/dev/null 2>&1
[[ "${#CURL_HEADERS_TO[@]}" -eq 2 ]] || {
    echo 'expected a crumb header to be configured on the to jenkins' >&2
    exit 1
}

# A refused trigger logs its HTTP status instead of a bare "HTTP error".
# Pass one parameter so the record is a realistic buildWithParameters call
# (and so the arrays are non-empty: bash 3.2 + `set -u` rejects expanding an
# empty array).
export STUB_POST_STATUS=403
unset STUB_BODY
param_record="$(printf '%s' '{"name":"JOB_SPEC","value":"stub"}' | base64)"
trigger_err="$fixture/trigger.err"
if ( trigger_job_build 'job/example/job/repo/job/build' "$param_record" ) >/dev/null 2>"$trigger_err"; then
    echo 'expected trigger_job_build to fail on HTTP 403' >&2
    exit 1
fi
grep -q 'POST .*buildWithParameters -> HTTP 403' "$trigger_err" || {
    echo 'trigger failure did not log the HTTP status:' >&2
    cat "$trigger_err" >&2
    exit 1
}

echo 'Jenkins migration verification tests passed.'
