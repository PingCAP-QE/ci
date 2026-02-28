#!/usr/bin/env bash
set -euo pipefail

# keep curl auth/header arrays always initialized for safe expansion
CURL_AUTH=()
CURL_HEADERS=()

usage() {
    cat <<'USAGE'
Replay Jenkins pipeline scripts from historical builds.

Single script replay:
  .ci/replay-jenkins-build.sh --script-file pipelines/.../job.groovy --build-url https://jenkins/job/.../1234 --wait

Auto replay all changed pipeline Groovy files in current PR/worktree:
  .ci/replay-jenkins-build.sh --auto-changed --base-sha <base_sha> --head-sha <head_sha> --wait

Options:
  --script-file <path>     Pipeline Groovy file to replay.
  --build-url <url>        Historical build URL used as replay source.
  --job-url <url>          Jenkins job URL. Used with --selector to choose historical build.
  --selector <name>        Build selector under job URL. Default: lastSuccessfulBuild.
  --auto-changed           Replay all changed pipelines/*.groovy from git diff.
  --base-sha <sha>         Base SHA for --auto-changed.
  --head-sha <sha>         Head SHA for --auto-changed.
  --jenkins-url <url>      Jenkins root URL. Default: $JENKINS_URL or https://do.pingcap.net/jenkins.
  --wait                   Wait for replay build to finish.
  --timeout <seconds>      Max wait seconds for queue/build completion. Default: 3600.
  --poll-interval <sec>    Poll interval in seconds. Default: 15.
  --max-replays <count>    Max replay count in --auto-changed mode. Default: 20.
  --verbose                Print detailed mapping and resolution logs.
  --dry-run                Print actions only.
  -h, --help               Show this help.

Environment:
  JENKINS_URL              Jenkins root URL fallback.
  JENKINS_USER             Jenkins username (required for replay submit).
  JENKINS_TOKEN            Jenkins API token/password paired with JENKINS_USER.
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

script_to_job_path() {
    local script_file="$1"
    local rel="${script_file#./}"

    [[ "$rel" == pipelines/* ]] || fatal "script path must be under pipelines/: ${script_file}"
    rel="${rel#pipelines/}"

    local IFS='/'
    # shellcheck disable=SC2206
    local parts=(${rel})
    local n="${#parts[@]}"

    (( n >= 3 )) || fatal "unexpected pipeline path: ${script_file}"

    local org="${parts[0]}"
    local repo="${parts[1]}"
    local last="${parts[n-1]}"
    local branch=""
    local job=""

    if [[ "$last" == "pipeline.groovy" ]]; then
        (( n >= 4 )) || fatal "unexpected pipeline path: ${script_file}"
        job="${parts[n-2]}"
        if (( n >= 5 )); then
            branch="${parts[2]}"
        fi
    elif [[ "$last" == *.groovy ]]; then
        job="${last%.groovy}"
        if (( n >= 4 )); then
            branch="${parts[2]}"
        fi
    else
        fatal "unsupported pipeline file: ${script_file}"
    fi

    local job_path="job/${org}/job/${repo}"
    if [[ -n "$branch" && "$branch" != "latest" ]]; then
        job_path+="/job/${branch}"
    fi
    job_path+="/job/${job}"

    printf '%s' "$job_path"
}

job_url_to_full_name() {
    local job_url="$1"
    local p
    p="${job_url#${JENKINS_URL}/}"
    p="${p#job/}"
    p="${p%/}"
    p="$(printf '%s' "$p" | sed 's#/job/#/#g')"
    printf '%s' "$p"
}

discover_changed_scripts() {
    local base_sha="$1"
    local head_sha="$2"

    if [[ -z "$base_sha" || -z "$head_sha" ]]; then
        if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
            base_sha="$(git merge-base origin/main HEAD)"
            head_sha="$(git rev-parse HEAD)"
        else
            base_sha="$(git rev-parse HEAD~1)"
            head_sha="$(git rev-parse HEAD)"
        fi
    fi

    log "collect changed pipeline files from ${base_sha}..${head_sha}"
    git diff --name-only "$base_sha" "$head_sha" | grep -E '^pipelines/.*\.groovy$' || true
}

setup_auth_and_crumb() {
    CURL_AUTH=()
    CURL_HEADERS=()

    if [[ -n "$JENKINS_USER" || -n "$JENKINS_TOKEN" ]]; then
        [[ -n "$JENKINS_USER" && -n "$JENKINS_TOKEN" ]] || fatal "both JENKINS_USER and JENKINS_TOKEN must be set together"
        CURL_AUTH=(-u "${JENKINS_USER}:${JENKINS_TOKEN}")
    fi

    local crumb_json
    if (( ${#CURL_AUTH[@]} )); then
        crumb_json="$(curl -sS "${CURL_AUTH[@]}" "${JENKINS_URL}/crumbIssuer/api/json" 2>/dev/null || true)"
    else
        crumb_json="$(curl -sS "${JENKINS_URL}/crumbIssuer/api/json" 2>/dev/null || true)"
    fi
    if [[ -n "$crumb_json" ]]; then
        local field
        local value
        field="$(jq -r '.crumbRequestField // empty' <<<"$crumb_json")"
        value="$(jq -r '.crumb // empty' <<<"$crumb_json")"
        if [[ -n "$field" && -n "$value" ]]; then
            CURL_HEADERS=(-H "${field}: ${value}")
        fi
    else
        log "crumb issuer unavailable, continuing without crumb header"
    fi
}

api_get() {
    local url="$1"
    curl -fsS "${CURL_AUTH[@]}" "$url"
}

api_get_with_status() {
    local url="$1"
    local body_file="$2"
    local status_code
    status_code="$(curl -sS "${CURL_AUTH[@]}" -o "$body_file" -w '%{http_code}' "$url" || true)"
    printf '%s' "$status_code"
}

api_post_script_text() {
    local script_file="$1"

    curl -fsS "${CURL_AUTH[@]}" "${CURL_HEADERS[@]}" \
        -X POST \
        --data-urlencode "script@${script_file}" \
        "${JENKINS_URL}/scriptText"
}

ensure_auth_for_replay() {
    [[ -n "$JENKINS_USER" && -n "$JENKINS_TOKEN" ]] || \
        fatal "JENKINS_USER and JENKINS_TOKEN are required for replay submit"
}

wait_queue_to_build_url() {
    local queue_url="$1"
    local timeout_sec="$2"
    local poll_sec="$3"

    local started
    started="$(date +%s)"

    while true; do
        local now
        now="$(date +%s)"
        if (( now - started > timeout_sec )); then
            fatal "timeout waiting queue item executable URL: ${queue_url}"
        fi

        local queue_json
        queue_json="$(api_get "${queue_url}/api/json")"

        local cancelled
        cancelled="$(jq -r '.cancelled // false' <<<"$queue_json")"
        if [[ "$cancelled" == "true" ]]; then
            local why
            why="$(jq -r '.why // "queue item cancelled"' <<<"$queue_json")"
            fatal "queue item cancelled: ${why}"
        fi

        local build_url
        build_url="$(jq -r '.executable.url // empty' <<<"$queue_json")"
        if [[ -n "$build_url" ]]; then
            printf '%s' "$(trim_trailing_slash "$build_url")"
            return
        fi

        sleep "$poll_sec"
    done
}

wait_build_result() {
    local build_url="$1"
    local timeout_sec="$2"
    local poll_sec="$3"

    local started
    started="$(date +%s)"

    while true; do
        local now
        now="$(date +%s)"
        if (( now - started > timeout_sec )); then
            fatal "timeout waiting replay build result: ${build_url}"
        fi

        local build_json
        build_json="$(api_get "${build_url}/api/json?tree=building,result,fullDisplayName,number,url")"

        local building
        building="$(jq -r '.building // false' <<<"$build_json")"
        local result
        result="$(jq -r '.result // empty' <<<"$build_json")"

        if [[ "$building" == "true" || -z "$result" || "$result" == "null" ]]; then
            sleep "$poll_sec"
            continue
        fi

        log "build finished: $(jq -r '.fullDisplayName // .url' <<<"$build_json") => ${result}"
        if [[ "$result" != "SUCCESS" ]]; then
            return 1
        fi
        return 0
    done
}

submit_replay() {
    local build_url="$1"
    local script_file="$2"
    local timeout_sec="$3"
    local poll_sec="$4"

    local job_url="${build_url%/*}"
    local source_build_num="${build_url##*/}"
    local job_full_name
    job_full_name="$(job_url_to_full_name "$job_url")"
    local script_b64
    script_b64="$(base64 < "$script_file" | tr -d '\n')"

    local groovy_file
    groovy_file="$(mktemp)"
    cat > "$groovy_file" <<EOF
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction
import java.util.Base64

def jobFullName = '${job_full_name}'
def sourceBuildNo = ${source_build_num}
def j = Jenkins.get()
def job = j.getItemByFullName(jobFullName)
if (job == null) { throw new RuntimeException("job not found: " + jobFullName) }
def sourceRun = job.getBuildByNumber(sourceBuildNo)
if (sourceRun == null) { throw new RuntimeException("build not found: " + jobFullName + "#" + sourceBuildNo) }
def replayAction = sourceRun.getAction(ReplayAction)
if (replayAction == null) { throw new RuntimeException("ReplayAction not found on " + jobFullName + "#" + sourceBuildNo) }
def mainScript = new String(Base64.decoder.decode('${script_b64}'), 'UTF-8')
def queueItem = replayAction.run2(mainScript, [:])
def rootUrl = j.getRootUrl() ?: '${JENKINS_URL}/'
println("job=" + jobFullName)
println("sourceBuild=" + sourceRun.getUrl())
println("queueId=" + queueItem.getId())
println("queueUrl=" + rootUrl + "queue/item/" + queueItem.getId() + "/")
EOF

    local submit_output
    submit_output="$(api_post_script_text "$groovy_file")"
    rm -f "$groovy_file"

    local source_build_raw
    source_build_raw="$(printf '%s\n' "$submit_output" | awk -F= '/^sourceBuild=/{print $2}' | tail -n1)"
    local queue_url
    queue_url="$(printf '%s\n' "$submit_output" | awk -F= '/^queueUrl=/{print $2}' | tail -n1)"

    [[ -n "$queue_url" ]] || fatal "failed to parse queueUrl from scriptText output: ${submit_output}"

    local source_build_url="$source_build_raw"
    if [[ -n "$source_build_url" && ! "$source_build_url" =~ ^https?:// ]]; then
        source_build_url="${JENKINS_URL}/${source_build_url}"
    fi

    if [[ -n "$source_build_url" ]]; then
        vlog "scriptText source build: $(trim_trailing_slash "$source_build_url")"
    fi
    vlog "scriptText queue URL: $(trim_trailing_slash "$queue_url")"

    local target_url
    target_url="$(wait_queue_to_build_url "$queue_url" "$timeout_sec" "$poll_sec")"
    printf '%s' "$(trim_trailing_slash "$target_url")"
}

replay_one() {
    local script_file="$1"
    local explicit_build_url="$2"
    local explicit_job_url="$3"
    REPLAY_LAST_RESULT=""

    [[ -f "$script_file" ]] || fatal "script file not found: ${script_file}"

    local job_url=""
    local build_url=""

    if [[ -n "$explicit_build_url" ]]; then
        build_url="$(trim_trailing_slash "$explicit_build_url")"
        job_url="${build_url%/*}"
        vlog "using explicit build URL: ${build_url}"
    else
        if [[ -n "$explicit_job_url" ]]; then
            job_url="$(trim_trailing_slash "$explicit_job_url")"
            vlog "using explicit job URL: ${job_url}"
        else
            local job_path
            job_path="$(script_to_job_path "$script_file")"
            job_url="${JENKINS_URL}/${job_path}"
            vlog "mapped script to job path: ${script_file} -> ${job_path}"
        fi

        build_url="${job_url}/${SELECTOR}"
        vlog "selected historical build from job URL: ${job_url} + ${SELECTOR}"
    fi

    build_url="$(trim_trailing_slash "$build_url")"

    if [[ "$DRY_RUN" != "true" ]]; then
        local resolved_body_file
        resolved_body_file="$(mktemp)"
        local status_code
        status_code="$(api_get_with_status "${build_url}/api/json?tree=url,number" "$resolved_body_file")"

        if [[ "$status_code" == "404" ]]; then
            log "skip replay (no historical build): ${build_url}"
            rm -f "$resolved_body_file"
            REPLAY_LAST_RESULT="skipped"
            return 0
        fi
        [[ "$status_code" =~ ^2[0-9][0-9]$ ]] || {
            log "resolve build URL failed (HTTP ${status_code}) for ${build_url}"
            sed -n '1,30p' "$resolved_body_file" >&2 || true
            rm -f "$resolved_body_file"
            fatal "failed to resolve concrete build URL from ${build_url}"
        }

        local resolved_json
        resolved_json="$(cat "$resolved_body_file")"
        rm -f "$resolved_body_file"
        local resolved_url
        resolved_url="$(jq -r '.url // empty' <<<"$resolved_json")"
        [[ -n "$resolved_url" ]] || fatal "failed to resolve concrete build URL from ${build_url}"

        build_url="$(trim_trailing_slash "$resolved_url")"
        vlog "resolved selector to concrete build URL: ${build_url}"
    fi

    log "replay source build: ${build_url}"
    log "pipeline script file: ${script_file}"

    if [[ "$DRY_RUN" == "true" ]]; then
        REPLAY_LAST_RESULT="dry-run"
        return 0
    fi

    local replay_build_url
    replay_build_url="$(submit_replay "$build_url" "$script_file" "$TIMEOUT_SEC" "$POLL_INTERVAL_SEC")"
    log "replay build assigned: ${replay_build_url}"

    if [[ "$WAIT_BUILD" == "true" ]]; then
        if ! wait_build_result "$replay_build_url" "$TIMEOUT_SEC" "$POLL_INTERVAL_SEC"; then
            printf 'replay failed: %s\n' "$replay_build_url" >&2
            REPLAY_LAST_RESULT="failed"
            return 1
        fi
        printf 'replay success: %s\n' "$replay_build_url"
    else
        printf 'replay submitted: %s\n' "$replay_build_url"
    fi
    REPLAY_LAST_RESULT="submitted"
}

init_defaults() {
    SCRIPT_FILE=""
    BUILD_URL=""
    JOB_URL=""
    SELECTOR="lastSuccessfulBuild"
    AUTO_CHANGED="false"
    BASE_SHA=""
    HEAD_SHA=""
    WAIT_BUILD="false"
    TIMEOUT_SEC=3600
    POLL_INTERVAL_SEC=15
    DRY_RUN="false"
    MAX_REPLAYS=20
    VERBOSE="false"
    JENKINS_URL="${JENKINS_URL:-https://do.pingcap.net/jenkins}"
    JENKINS_USER="${JENKINS_USER:-}"
    JENKINS_TOKEN="${JENKINS_TOKEN:-}"
    REPLAY_LAST_RESULT=""
    SUMMARY_SUBMITTED=0
    SUMMARY_SKIPPED=0
    SUMMARY_FAILED=0
    SUMMARY_DRY_RUN=0
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --script-file)
                SCRIPT_FILE="$2"
                shift 2
                ;;
            --build-url)
                BUILD_URL="$2"
                shift 2
                ;;
            --job-url)
                JOB_URL="$2"
                shift 2
                ;;
            --selector)
                SELECTOR="$2"
                shift 2
                ;;
            --auto-changed)
                AUTO_CHANGED="true"
                shift
                ;;
            --base-sha)
                BASE_SHA="$2"
                shift 2
                ;;
            --head-sha)
                HEAD_SHA="$2"
                shift 2
                ;;
            --jenkins-url)
                JENKINS_URL="$2"
                shift 2
                ;;
            --wait)
                WAIT_BUILD="true"
                shift
                ;;
            --timeout)
                TIMEOUT_SEC="$2"
                shift 2
                ;;
            --poll-interval)
                POLL_INTERVAL_SEC="$2"
                shift 2
                ;;
            --max-replays)
                MAX_REPLAYS="$2"
                shift 2
                ;;
            --verbose)
                VERBOSE="true"
                shift
                ;;
            --dry-run)
                DRY_RUN="true"
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                fatal "unknown argument: $1"
                ;;
        esac
    done
}

validate_inputs() {
    require_bin curl
    require_bin jq
    require_bin git
    require_bin base64
    require_bin sed

    JENKINS_URL="$(trim_trailing_slash "$JENKINS_URL")"

    [[ "$TIMEOUT_SEC" =~ ^[0-9]+$ ]] || fatal "--timeout must be integer seconds"
    [[ "$POLL_INTERVAL_SEC" =~ ^[0-9]+$ ]] || fatal "--poll-interval must be integer seconds"
    [[ "$MAX_REPLAYS" =~ ^[0-9]+$ ]] || fatal "--max-replays must be integer count"

    if [[ "$AUTO_CHANGED" == "true" ]]; then
        [[ -z "$SCRIPT_FILE" ]] || fatal "--script-file cannot be used with --auto-changed"
        [[ -z "$BUILD_URL" ]] || fatal "--build-url cannot be used with --auto-changed"
        [[ -z "$JOB_URL" ]] || fatal "--job-url cannot be used with --auto-changed"
    else
        [[ -n "$SCRIPT_FILE" ]] || fatal "--script-file is required unless --auto-changed is set"
    fi
}

record_summary() {
    local result="$1"
    case "$result" in
        submitted)
            ((SUMMARY_SUBMITTED+=1))
            ;;
        skipped)
            ((SUMMARY_SKIPPED+=1))
            ;;
        failed)
            ((SUMMARY_FAILED+=1))
            ;;
        dry-run)
            ((SUMMARY_DRY_RUN+=1))
            ;;
        "")
            ;;
        *)
            vlog "unknown replay result: ${result}"
            ;;
    esac
}

print_summary() {
    local summary="replay summary: submitted=${SUMMARY_SUBMITTED}, skipped=${SUMMARY_SKIPPED}, failed=${SUMMARY_FAILED}"
    if (( SUMMARY_DRY_RUN > 0 )); then
        summary+=", dry-run=${SUMMARY_DRY_RUN}"
    fi
    log "$summary"
}

run_main_flow() {
    local failed=0

    setup_auth_and_crumb
    if [[ "$DRY_RUN" != "true" ]]; then
        ensure_auth_for_replay
    fi

    if [[ "$AUTO_CHANGED" == "true" ]]; then
        local changed_scripts=()
        local script=""
        while IFS= read -r script; do
            [[ -n "$script" ]] || continue
            changed_scripts+=("$script")
        done < <(discover_changed_scripts "$BASE_SHA" "$HEAD_SHA")

        if (( ${#changed_scripts[@]} == 0 )); then
            log "no changed pipelines/*.groovy detected; nothing to replay"
            print_summary
            return 0
        fi
        vlog "changed pipeline files count: ${#changed_scripts[@]}"
        for script in "${changed_scripts[@]}"; do
            vlog "changed pipeline file: ${script}"
        done
        if (( ${#changed_scripts[@]} > MAX_REPLAYS )); then
            fatal "replay file count ${#changed_scripts[@]} exceeds --max-replays ${MAX_REPLAYS}"
        fi

        for script in "${changed_scripts[@]}"; do
            if replay_one "$script" "" ""; then
                record_summary "${REPLAY_LAST_RESULT}"
            else
                record_summary "${REPLAY_LAST_RESULT:-failed}"
                failed=1
            fi
        done
    else
        if replay_one "$SCRIPT_FILE" "$BUILD_URL" "$JOB_URL"; then
            record_summary "${REPLAY_LAST_RESULT}"
        else
            record_summary "${REPLAY_LAST_RESULT:-failed}"
            failed=1
        fi
    fi

    print_summary
    return "$failed"
}

main() {
    init_defaults
    parse_args "$@"
    validate_inputs
    run_main_flow
}

main "$@"
