#!/usr/bin/env bash
set -euo pipefail

# keep curl auth/header arrays always initialized for safe expansion
CURL_AUTH_FROM=()
CURL_HEADERS_FROM=()
CURL_AUTH_TO=()
CURL_HEADERS_TO=()

COMMENT_MARKER="<!-- ci-migration-verify-status -->"

usage() {
    cat <<'USAGE'
Verify a jenkins-agent Prow job migration PR.

Detects prow-jobs whose labels.master flipped "1" -> "0" in the PR diff,
copies the parameters of the last successful build from the from Jenkins and
triggers the same job on the to Jenkins, optionally waiting for completion.
Parameters fall back to the most recent build (any result) when there is no
successful build. Failed builds are classified as infra (checkout/SSH/bazel
fetch errors, ABORTED) vs real test failures and reported separately.
Reports progress via a single PR comment.

Usage:
  .ci/verify-jenkins-migration.sh --base-sha <sha> --head-sha <sha> [options]

Required:
  --base-sha <sha>        Base commit of the diff (merge base).
  --head-sha <sha>        Head commit of the diff (PR head).

Options:
  --parallel <count>      Max concurrent verifications. Default: 10.
  --max-jobs <count>      Max jobs verified per run. Default: 40.
  --wait                  Wait for each triggered build to finish.
  --timeout <seconds>     Max wait seconds per queue/build. Default: 7200.
  --poll-interval <sec>   Poll interval in seconds. Default: 20.
  --retries <count>       Trigger retries for infra failures. Default: 3.
  --source-build <url>    Copy parameters from this from-jenkins build URL
                          instead of <job>/lastSuccessfulBuild (e.g. a recent
                          pingcap/tidb PR's successful build, to get version-
                          matched parameters). Falls back to lastSuccessfulBuild
                          -> lastBuild when unset.
  --repo <owner/repo>     GitHub repo for PR comment reporting (auto from env).
  --pr <number>           PR number for comment reporting (auto from env).
  --dry-run               Detect and plan only, do not trigger.
  --verbose               Print verbose logs.
  -h, --help              Show this help.

Environment:
  FROM_JENKINS_URL / FROM_JENKINS_USER / FROM_JENKINS_TOKEN   from (source) Jenkins
  TO_JENKINS_URL / TO_JENKINS_USER / TO_JENKINS_TOKEN         to (target) Jenkins
  GITHUB_TOKEN (optional)   for PR comment reporting
USAGE
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
}

vlog() {
    if [[ "${VERBOSE:-false}" == "true" ]]; then
        log "$*"
    fi
}

fatal() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_bin() {
    local bin="$1"
    command -v "$bin" >/dev/null 2>&1 || fatal "missing required command: ${bin}"
}

trim_trailing_slash() {
    local s="$1"
    while [[ "$s" == */ ]]; do
        s="${s%/}"
    done
    printf '%s' "$s"
}

setup_jenkins_env() {
    local kind="$1" url user token auth
    case "$kind" in
        from)
            url="$FROM_JENKINS_URL"; user="$FROM_JENKINS_USER"; token="$FROM_JENKINS_TOKEN"
            ;;
        to)
            url="$TO_JENKINS_URL"; user="$TO_JENKINS_USER"; token="$TO_JENKINS_TOKEN"
            ;;
        *)
            fatal "unknown jenkins kind: ${kind}"
            ;;
    esac
    [[ -n "$url" ]] || fatal "${kind} jenkins url is required (env ${kind^^}_JENKINS_URL)"
    if [[ -n "$user" || -n "$token" ]]; then
        [[ -n "$user" && -n "$token" ]] || fatal "${kind} jenkins user and token must be set together"
    fi
    case "$kind" in
        from) FROM_JENKINS_URL="$(trim_trailing_slash "$url")"; ;;
        to) TO_JENKINS_URL="$(trim_trailing_slash "$url")"; ;;
    esac
}

setup_auth() {
    local kind="$1" url user token
    case "$kind" in
        from)
            url="$FROM_JENKINS_URL"; user="$FROM_JENKINS_USER"; token="$FROM_JENKINS_TOKEN"
            CURL_AUTH_FROM=()
            if [[ -n "$user" ]]; then CURL_AUTH_FROM=(-u "${user}:${token}"); fi
            ;;
        to)
            url="$TO_JENKINS_URL"; user="$TO_JENKINS_USER"; token="$TO_JENKINS_TOKEN"
            CURL_AUTH_TO=()
            if [[ -n "$user" ]]; then CURL_AUTH_TO=(-u "${user}:${token}"); fi
            ;;
    esac

    local crumb_json field value
    if [[ "$kind" == "from" ]]; then
        crumb_json="$(curl -sS "${CURL_AUTH_FROM[@]}" "${url}/crumbIssuer/api/json" 2>/dev/null || true)"
    else
        crumb_json="$(curl -sS "${CURL_AUTH_TO[@]}" "${url}/crumbIssuer/api/json" 2>/dev/null || true)"
    fi
    if [[ -n "$crumb_json" ]]; then
        field="$(jq -r '.crumbRequestField // empty' <<<"$crumb_json")"
        value="$(jq -r '.crumb // empty' <<<"$crumb_json")"
        if [[ -n "$field" && -n "$value" ]]; then
            case "$kind" in
                from) CURL_HEADERS_FROM=(-H "${field}: ${value}"); ;;
                to) CURL_HEADERS_TO=(-H "${field}: ${value}"); ;;
            esac
        fi
    else
        vlog "crumb issuer unavailable for ${kind} jenkins, continuing without crumb header"
    fi
}

api_get_with_status() {
    local url="$1" body_file="$2" status
    status="$(curl -sS -g "${CURL_AUTH_TO[@]}" -o "$body_file" -w '%{http_code}' "$url" || true)"
    printf '%s' "$status"
}

# Extract jenkins-agent jobs from a yaml file: "name\tmaster" per line.
extract_jenkins_jobs() {
    local f="$1"
    [[ -f "$f" ]] || return 0
    yq -r --yaml-fix-merge-anchor-to-spec=true '.. | select(.agent? == "jenkins") | [.name // "", (.labels.master // "")] | @tsv' "$f" 2>/dev/null || true
}

# job name -> jenkins job path (org/repo/branch/job => job/org/job/repo/job/branch/job/job)
job_name_to_path() {
    local name="$1"
    printf 'job/%s' "$(printf '%s' "$name" | sed 's#/#/job/#g')"
}

detect_flipped_jobs() {
    local file="$1" basejobs headjobs bname bmaster hname hmaster btmp base_tmp
    basejobs="$(mktemp)"
    headjobs="$(mktemp)"
    base_tmp="$(mktemp)"
    # base version of the file (empty if file is newly added)
    if git show "${BASE_SHA}:${file}" > "$base_tmp" 2>/dev/null; then
        extract_jenkins_jobs "$base_tmp" > "$basejobs"
    fi
    rm -f "$base_tmp"
    extract_jenkins_jobs "$file" > "$headjobs"

    while IFS=$'\t' read -r hname hmaster; do
        [[ -n "$hname" ]] || continue
        [[ "$hmaster" == "0" ]] || continue
        [[ "$hname" =~ next[-_]?gen ]] && continue
        bmaster=""
        while IFS=$'\t' read -r bname btmp; do
            [[ "$bname" == "$hname" ]] && { bmaster="$btmp"; break; }
        done < "$basejobs"
        if [[ "$bmaster" == "1" ]]; then
            printf '%s\n' "$hname"
        fi
    done < "$headjobs"

    rm -f "$basejobs" "$headjobs"
}

collect_flipped_jobs() {
    local base_sha="$1" head_sha="$2" file jobs
    git diff --name-only --diff-filter=ACMRTUXB "$base_sha" "$head_sha" | rg '^prow-jobs/.*\.ya?ml$' | while IFS= read -r file; do
        jobs="$(detect_flipped_jobs "$file")"
        if [[ -n "$jobs" ]]; then
            while IFS= read -r j; do
                [[ -n "$j" ]] || continue
                printf '%s\t%s\n' "$j" "$file"
            done <<< "$jobs"
        fi
    done
}

# Extract parameters from a build URL on the from jenkins; exits non-zero when
# the build does not exist.
get_params_from_build() {
    local url="$1" body
    body="$(curl -fsS -g "${CURL_AUTH_FROM[@]}" "$url" 2>/dev/null || curl -fsS -g "$url" 2>/dev/null)" || return 1
    jq -c '[.actions[]?.parameters[]? | select(.name? != null) | {name, value: ((.value // "") | tostring)}]' <<<"$body" | \
        jq -r '.[] | @base64' || return 1
}

# Output base64-encoded JSON records {name,value} of the parameters used to
# trigger a job on the to jenkins. Prefers the from jenkins last successful
# build; falls back to the most recent build (any result) so jobs without a
# successful build history are still verified. When no build exists at all,
# returns empty (the job is triggered without parameters). Never skips.
get_last_success_params() {
    local job_path="$1" body url
    if [[ -n "$SOURCE_BUILD" ]]; then
        url="${SOURCE_BUILD%/}/api/json?tree=actions[parameters[name,value]]"
        if body="$(get_params_from_build "$url" 2>/dev/null)"; then
            PARAMS_SOURCE="source-build"
        else
            PARAMS_SOURCE="source-build-unavailable"
            body=""
        fi
        vlog "params source for ${job_path}: ${PARAMS_SOURCE}"
        printf '%s' "$body"
        return 0
    fi
    url="${FROM_JENKINS_URL}/${job_path}/lastSuccessfulBuild/api/json?tree=actions[parameters[name,value]]"
    if body="$(get_params_from_build "$url" 2>/dev/null)"; then
        PARAMS_SOURCE="lastSuccessfulBuild"
    else
        url="${FROM_JENKINS_URL}/${job_path}/lastBuild/api/json?tree=actions[parameters[name,value]]"
        if body="$(get_params_from_build "$url" 2>/dev/null)"; then
            PARAMS_SOURCE="lastBuild"
        else
            PARAMS_SOURCE="none"
            body=""
        fi
    fi
    vlog "params source for ${job_path}: ${PARAMS_SOURCE}"
    printf '%s' "$body"
    return 0
}

trigger_job_build() {
    local job_path="$1" params_b64="$2"
    local -a args=()
    local -a tmpfiles=()
    local rec vf name value
    while IFS= read -r rec; do
        [[ -n "$rec" ]] || continue
        vf="$(mktemp)"
        printf '%s' "$rec" | base64 -d > "$vf"
        name="$(jq -r '.name' "$vf")"
        value="$(jq -r '.value' "$vf")"
        printf '%s' "$value" > "${vf}.v"
        args+=(--data-urlencode "${name}@${vf}.v")
        tmpfiles+=("$vf" "${vf}.v")
    done <<< "$params_b64"

    # Parameterized jobs reject POST /build with HTTP 400; non-parameterized
    # jobs reject POST /buildWithParameters. When we have parameters we must
    # use buildWithParameters. When we have none we try build first and fall
    # back to buildWithParameters (which applies the job's default parameter
    # values) so jobs without build history are still triggered.
    local -a endpoints=("build")
    if (( ${#args[@]} > 0 )); then
        endpoints=("buildWithParameters")
    else
        endpoints=("build" "buildWithParameters")
    fi

    local endpoint headers status loc
    for endpoint in "${endpoints[@]}"; do
        headers="$(mktemp)"
        status="$(curl -sS "${CURL_AUTH_TO[@]}" "${CURL_HEADERS_TO[@]}" -o /dev/null -D "$headers" \
            -w '%{http_code}' -X POST "${args[@]}" "${TO_JENKINS_URL}/${job_path}/${endpoint}" || true)"
        loc="$(awk -F': ' 'tolower($1)=="location" {gsub("\r", "", $2); print $2; exit}' "$headers")"
        rm -f "$headers"
        if { [[ "$status" == "200" || "$status" == "201" ]]; } && [[ -n "$loc" ]]; then
            local tf
            for tf in "${tmpfiles[@]}"; do
                rm -f "$tf"
            done
            printf '%s' "$(trim_trailing_slash "$loc")"
            return 0
        fi
    done
    local tf
    for tf in "${tmpfiles[@]}"; do
        rm -f "$tf"
    done
    return 1
}

wait_queue_to_build_url() {
    local queue_url="$1" timeout_sec="$2" poll_sec="$3" started now
    started="$(date +%s)"
    while true; do
        now="$(date +%s)"
        if (( now - started > timeout_sec )); then
            log "timeout waiting queue item executable URL: ${queue_url}"
            return 1
        fi
        local body_file
        body_file="$(mktemp)"
        local status
        status="$(api_get_with_status "${queue_url}/api/json" "$body_file")"
        if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
            local cancelled build_url
            cancelled="$(jq -r '.cancelled // false' "$body_file")"
            build_url="$(jq -r '.executable.url // empty' "$body_file")"
            rm -f "$body_file"
            if [[ "$cancelled" == "true" ]]; then
                log "queue item cancelled: $(jq -r '.why // "cancelled"' "$body_file" 2>/dev/null || true)"
                return 1
            fi
            if [[ -n "$build_url" ]]; then
                printf '%s' "$(trim_trailing_slash "$build_url")"
                return 0
            fi
        else
            rm -f "$body_file"
            if [[ "$status" == "404" ]]; then
                log "queue item disappeared: ${queue_url}"
                return 1
            fi
        fi
        sleep "$poll_sec"
    done
}

wait_build_result() {
    local build_url="$1" timeout_sec="$2" poll_sec="$3" started now
    started="$(date +%s)"
    while true; do
        now="$(date +%s)"
        if (( now - started > timeout_sec )); then
            log "timeout waiting build result: ${build_url}"
            return 1
        fi
        local body_file
        body_file="$(mktemp)"
        local status
        status="$(api_get_with_status "${build_url}/api/json?tree=building,result,url" "$body_file")"
        if [[ "$status" =~ ^2[0-9][0-9]$ ]]; then
            local building result
            building="$(jq -r '.building // false' "$body_file")"
            result="$(jq -r '.result // empty' "$body_file")"
            rm -f "$body_file"
            if [[ "$building" != "true" && -n "$result" && "$result" != "null" ]]; then
                log "build finished: ${build_url} => ${result}"
                BUILD_RESULT="$result"
                if [[ "$result" != "SUCCESS" ]]; then
                    return 1
                fi
                return 0
            fi
        else
            rm -f "$body_file"
            if [[ "$status" == "404" ]]; then
                log "build disappeared: ${build_url}"
                return 1
            fi
        fi
        sleep "$poll_sec"
    done
}

# Known infra/network failure markers in a Jenkins console log (checkout/SSH
# and bazel fetch errors). Jobs failing with these are infra flakes, not real
# test failures.
INFRA_FAIL_PATTERNS='Connection closed by|Error fetching remote repo|Could not read from remote repository|unexpected end of file|No valid crumb was included'

# Classify a failed build as "infra" or "test" by grepping its console log.
# Only the last 1MB of the console is fetched to bound the transfer size.
classify_build_failure() {
    local build_url="$1" console
    console="$(curl -fsS -g -r -1048576 "${CURL_AUTH_TO[@]}" "${build_url}/consoleText" 2>/dev/null || true)"
    if [[ -n "$console" ]] && printf '%s' "$console" | rg -q "$INFRA_FAIL_PATTERNS"; then
        printf 'infra'
        return 0
    fi
    printf 'test'
    return 0
}

verify_one() {
    local name="$1" source_file="$2"
    REPO_LAST_RESULT=""

    local job_path
    job_path="$(job_name_to_path "$name")"
    log "verify ${name} (${source_file}) -> ${TO_JENKINS_URL}/${job_path}"

    local params_b64=""
    params_b64="$(get_last_success_params "$job_path")"
    if [[ "$PARAMS_SOURCE" == "none" ]]; then
        log "no build history on from jenkins for ${name}; triggering without parameters"
    fi

    if [[ "$DRY_RUN" == "true" ]]; then
        local n=0
        [[ -n "$params_b64" ]] && n="$(printf '%s\n' "$params_b64" | wc -l | tr -d ' ')"
        log "dry-run: would trigger ${name} on ${TO_JENKINS_URL}/${job_path} with ${n} parameters"
        REPO_LAST_RESULT="dry-run"
        return 0
    fi

    local attempt build_url
    for (( attempt = 1; attempt <= RETRIES; attempt++ )); do
        local queue_url
        if queue_url="$(trigger_job_build "$job_path" "$params_b64")"; then
            log "triggered ${name} (attempt ${attempt}): queue ${queue_url}"
        else
            log "trigger failed for ${name} (attempt ${attempt}/${RETRIES}): HTTP error"
            [[ $attempt -lt RETRIES ]] && sleep 10
            continue
        fi

        build_url="$(wait_queue_to_build_url "$queue_url" "$TIMEOUT_SEC" "$POLL_INTERVAL_SEC")" || {
            log "queue resolve failed for ${name}"
            REPO_LAST_RESULT="failed"
            return 1
        }
        log "build assigned for ${name}: ${build_url}"
        break
    done

    if [[ -z "${build_url:-}" ]]; then
        log "gave up triggering ${name} after ${RETRIES} attempts"
        REPO_LAST_RESULT="failed"
        return 1
    fi

    if [[ "$WAIT_BUILD" == "true" ]]; then
        if wait_build_result "$build_url" "$TIMEOUT_SEC" "$POLL_INTERVAL_SEC"; then
            REPO_LAST_RESULT="success"
            return 0
        fi
        if [[ "$BUILD_RESULT" == "ABORTED" ]]; then
            log "build aborted for ${name} (infra)"
            REPO_LAST_RESULT="infra-fail"
            return 1
        fi
        if [[ "$(classify_build_failure "$build_url")" == "infra" ]]; then
            log "build failure classified as infra for ${name}"
            REPO_LAST_RESULT="infra-fail"
            return 1
        fi
        log "build failure classified as test failure for ${name}"
        REPO_LAST_RESULT="failed"
        return 1
    fi
    REPO_LAST_RESULT="submitted"
    return 0
}

run_parallel_verify() {
    local -a names=("$@")
    local pids=() pid_to_idx=() next=0 running=0 failed=0
    local done_pid="" done_idx="" exit_code=0 result="" p alive=()

    spawn_next() {
        local idx="$1"
        local name="${names[$idx]}"
        local label="$name"
        (
            log() {
                printf '[%s] [%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$label" "$*" >&2
            }
            vlog() {
                if [[ "${VERBOSE:-false}" == "true" ]]; then
                    log "$*"
                fi
            }
            if verify_one "$name" "${SRC_FILES[$idx]}"; then
                printf '%s' "${REPO_LAST_RESULT}" > "${RESULTS_DIR}/$((idx+1)).result"
                exit 0
            fi
            printf '%s' "${REPO_LAST_RESULT:-failed}" > "${RESULTS_DIR}/$((idx+1)).result"
            exit 1
        ) &
        pids+=($!)
        pid_to_idx[$!]="$idx"
        running=$((running+1))
    }

    while (( next < ${#names[@]} || running > 0 )); do
        while (( running < PARALLEL && next < ${#names[@]} )); do
            spawn_next "$next"
            next=$((next+1))
        done

        done_pid=""
        exit_code=0
        wait -n -p done_pid "${pids[@]}" 2>/dev/null
        exit_code=$?
        if [[ -z "$done_pid" ]]; then
            break
        fi

        done_idx="${pid_to_idx[$done_pid]}"
        alive=()
        for p in "${pids[@]}"; do
            [[ "$p" != "$done_pid" ]] && alive+=("$p")
        done
        pids=("${alive[@]}")
        unset "pid_to_idx[$done_pid]"
        running=$((running-1))

        result="$(cat "${RESULTS_DIR}/$((done_idx+1)).result" 2>/dev/null || echo failed)"
        record_summary "$result"
        log "--- verify finished: ${names[$done_idx]} -> ${result} (exit ${exit_code}) ---"
        if [[ "$exit_code" != "0" ]]; then
            failed=1
        fi
    done

    return "$failed"
}

# --- summary accounting (kept simple; results are finalized by the caller) ---
SUMMARY_SUCCESS=0
SUMMARY_SUBMITTED=0
SUMMARY_SKIPPED=0
SUMMARY_FAILED=0
SUMMARY_INFRA_FAIL=0
SUMMARY_DRY_RUN=0
SUMMARY_TOTAL=0

record_summary() {
    local r="$1"
    case "$r" in
        success) ((SUMMARY_SUCCESS+=1)) ;;
        submitted) ((SUMMARY_SUBMITTED+=1)) ;;
        skipped) ((SUMMARY_SKIPPED+=1)) ;;
        failed) ((SUMMARY_FAILED+=1)) ;;
        infra-fail) ((SUMMARY_INFRA_FAIL+=1)) ;;
        dry-run) ((SUMMARY_DRY_RUN+=1)) ;;
    esac
}

# --- PR comment reporting ---
report_resolve_repo_pr() {
    if [[ -z "$REPO" ]]; then
        REPO="${REPO_OWNER:-}/${REPO_NAME:-}"
        [[ "$REPO" == "/" ]] && REPO=""
    fi
    if [[ -z "$PR" ]]; then
        PR="${PULL_NUMBER:-}"
    fi
}

post_or_update_comment() {
    local body_file="$1"
    report_resolve_repo_pr
    [[ -n "$REPO" && -n "$PR" ]] || return 0
    command -v gh >/dev/null 2>&1 || return 0

    local api="repos/${REPO}/issues/${PR}/comments"
    local existing
    existing="$(gh api "$api" --jq "[.[] | select(.body | startswith(\"${COMMENT_MARKER}\"))][0].id // empty" 2>/dev/null || true)"
    if [[ -n "$existing" ]]; then
        gh api -X PATCH "repos/${REPO}/issues/comments/${existing}" -F "body=@${body_file}" >/dev/null 2>&1 || true
    else
        gh api "$api" -F "body=@${body_file}" >/dev/null 2>&1 || true
    fi
}

render_report() {
    local out_file="$1" names="$2" results_dir="$3"
    {
        printf '%s\n' "${COMMENT_MARKER}"
        printf '## Jenkins Migration Verification\n\n'
        printf 'Migrating jenkins-agent Prow jobs to the to Jenkins (`labels.master`: 1 -> 0).\n\n'
        local idx=0 name r
        while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            idx=$((idx+1))
            r="$(cat "${results_dir}/${idx}.result" 2>/dev/null || echo pending)"
            case "$r" in
                success) box="x" ;;
                *) box=" " ;;
            esac
            printf -- '- [%s] `%s`\n  - status: %s\n' "$box" "$name" "$r"
        done <<< "$names"
        printf '\nSummary: success=%d submitted=%d failed=%d infra-fail=%d skipped=%d dry-run=%d total=%d\n' \
            "$SUMMARY_SUCCESS" "$SUMMARY_SUBMITTED" "$SUMMARY_FAILED" "$SUMMARY_INFRA_FAIL" "$SUMMARY_SKIPPED" "$SUMMARY_DRY_RUN" "$SUMMARY_TOTAL"
    } > "$out_file"
}

init_defaults() {
    BASE_SHA=""
    HEAD_SHA=""
    PARALLEL=10
    MAX_JOBS=40
    WAIT_BUILD="false"
    TIMEOUT_SEC=7200
    POLL_INTERVAL_SEC=20
    RETRIES=3
    DRY_RUN="false"
    VERBOSE="false"
    REPO=""
    PR=""
    SOURCE_BUILD=""
    REPO_LAST_RESULT=""
    BUILD_RESULT=""
    PARAMS_SOURCE=""
    FROM_JENKINS_URL="${FROM_JENKINS_URL:-}"
    FROM_JENKINS_USER="${FROM_JENKINS_USER:-}"
    FROM_JENKINS_TOKEN="${FROM_JENKINS_TOKEN:-}"
    TO_JENKINS_URL="${TO_JENKINS_URL:-}"
    TO_JENKINS_USER="${TO_JENKINS_USER:-}"
    TO_JENKINS_TOKEN="${TO_JENKINS_TOKEN:-}"
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --base-sha) BASE_SHA="$2"; shift 2 ;;
            --head-sha) HEAD_SHA="$2"; shift 2 ;;
            --parallel) PARALLEL="$2"; shift 2 ;;
            --max-jobs) MAX_JOBS="$2"; shift 2 ;;
            --wait) WAIT_BUILD="true"; shift ;;
            --timeout) TIMEOUT_SEC="$2"; shift 2 ;;
            --poll-interval) POLL_INTERVAL_SEC="$2"; shift 2 ;;
            --retries) RETRIES="$2"; shift 2 ;;
            --source-build) SOURCE_BUILD="$2"; shift 2 ;;
            --repo) REPO="$2"; shift 2 ;;
            --pr) PR="$2"; shift 2 ;;
            --dry-run) DRY_RUN="true"; shift ;;
            --verbose) VERBOSE="true"; shift ;;
            -h|--help) usage; exit 0 ;;
            *) fatal "unknown argument: $1" ;;
        esac
    done
}

validate_inputs() {
    require_bin curl
    require_bin jq
    require_bin git
    require_bin rg
    require_bin yq
    require_bin base64
    require_bin awk
    require_bin sed

    [[ -n "$BASE_SHA" ]] || fatal "--base-sha is required"
    [[ -n "$HEAD_SHA" ]] || fatal "--head-sha is required"
    [[ "$PARALLEL" =~ ^[0-9]+$ ]] && [[ "$PARALLEL" -ge 1 ]] || fatal "--parallel must be an integer >= 1"
    if (( PARALLEL > 1 )); then
        [[ "${BASH_VERSINFO[0]}" -ge 5 ]] || fatal "--parallel > 1 requires bash 5.1+ (found ${BASH_VERSION})"
        [[ "${BASH_VERSINFO[0]}" -gt 5 || "${BASH_VERSINFO[1]}" -ge 1 ]] || fatal "--parallel > 1 requires bash 5.1+ (found ${BASH_VERSION})"
    fi
    [[ "$MAX_JOBS" =~ ^[0-9]+$ ]] && [[ "$MAX_JOBS" -ge 1 ]] || fatal "--max-jobs must be an integer >= 1"
    [[ "$RETRIES" =~ ^[0-9]+$ ]] || fatal "--retries must be an integer"
    setup_jenkins_env from
    setup_jenkins_env to
}

run_main_flow() {
    local names=() src_files=() jobs_list
    local line name file
    local tmpdir results_dir body_file

    setup_auth from
    setup_auth to

    log "detecting jenkins-agent job migration flips in ${BASE_SHA}..${HEAD_SHA}"
    jobs_list="$(collect_flipped_jobs "$BASE_SHA" "$HEAD_SHA" || true)"
    if [[ -z "$jobs_list" ]]; then
        log "no migrated jobs detected (labels.master 1 -> 0); nothing to verify"
        return 0
    fi

    while IFS=$'\t' read -r name file; do
        [[ -n "$name" ]] || continue
        names+=("$name")
        src_files+=("$file")
    done <<< "$jobs_list"

    SUMMARY_TOTAL="${#names[@]}"
    if (( SUMMARY_TOTAL > MAX_JOBS )); then
        fatal "detected ${SUMMARY_TOTAL} migrated jobs, exceeds --max-jobs ${MAX_JOBS}"
    fi

    log "detected ${SUMMARY_TOTAL} migrated jobs:"
    local i
    for (( i = 0; i < SUMMARY_TOTAL; i++ )); do
        vlog "  ${names[$i]} (${src_files[$i]})"
        log "job: ${names[$i]} (${src_files[$i]})"
    done

    tmpdir="$(mktemp -d)"
    results_dir="$tmpdir/results"
    mkdir -p "$results_dir"
    RESULTS_DIR="$results_dir"

    # write per-job placeholder results for report rendering
    for (( i = 0; i < SUMMARY_TOTAL; i++ )); do
        printf '%s\n' "pending" > "${results_dir}/$((i+1)).result"
    done

    body_file="$tmpdir/comment.md"
    render_report "$body_file" "$(printf '%s\n' "${names[@]}")" "$results_dir"
    post_or_update_comment "$body_file"

    local failed=0
    if (( PARALLEL > 1 )); then
        # run_parallel_verify mutates the summary counters inside subshells; the
        # counters are re-derived from the results files below.
        SRC_FILES=("${src_files[@]}")
        if ! run_parallel_verify "${names[@]}"; then
            failed=1
        fi
    else
        local idx=0
        for name in "${names[@]}"; do
            idx=$((idx+1))
            if verify_one "$name" "${src_files[$((idx-1))]}"; then
                record_summary "${REPO_LAST_RESULT}"
            else
                record_summary "${REPO_LAST_RESULT:-failed}"
                failed=1
            fi
            printf '%s\n' "${REPO_LAST_RESULT:-failed}" > "${results_dir}/${idx}.result"
        done
    fi

    # Re-derive summary counters from per-job results so parallel runs report accurately.
    local r
    SUMMARY_SUCCESS=0; SUMMARY_SUBMITTED=0; SUMMARY_SKIPPED=0; SUMMARY_FAILED=0; SUMMARY_INFRA_FAIL=0; SUMMARY_DRY_RUN=0
    for (( i = 1; i <= SUMMARY_TOTAL; i++ )); do
        r="$(cat "${results_dir}/${i}.result" 2>/dev/null || echo failed)"
        record_summary "$r"
    done

    render_report "$body_file" "$(printf '%s\n' "${names[@]}")" "$results_dir"
    post_or_update_comment "$body_file"

    log "verify summary: success=${SUMMARY_SUCCESS} submitted=${SUMMARY_SUBMITTED} failed=${SUMMARY_FAILED} infra-fail=${SUMMARY_INFRA_FAIL} skipped=${SUMMARY_SKIPPED} dry-run=${SUMMARY_DRY_RUN} total=${SUMMARY_TOTAL}"
    rm -rf "$tmpdir"
    return "$failed"
}

main() {
    init_defaults
    parse_args "$@"
    validate_inputs
    run_main_flow
}

main "$@"
