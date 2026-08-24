#!/usr/bin/env bash
set -euo pipefail

# Trigger a Jenkins job (default: seed) on the Jenkins instance pointed to by
# JENKINS_URL, using JENKINS_USER/JENKINS_TOKEN for auth, with CSRF crumb
# handling. Passes the standard prow post-submit parameters (BUILD_ID,
# PROW_JOB_ID, JOB_SPEC) when present in the environment, so both the from and
# to Jenkins seed jobs receive the same context they get from a prow-triggered
# run. Optionally waits for the triggered build to finish.

JENKINS_URL="${JENKINS_URL:-}"
JENKINS_USER="${JENKINS_USER:-}"
JENKINS_TOKEN="${JENKINS_TOKEN:-}"
JENKINS_JOB="${JENKINS_JOB:-seed}"
WAIT_BUILD="${WAIT_BUILD:-false}"
TIMEOUT_SEC="${TIMEOUT_SEC:-1800}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-10}"
COOKIE_JAR=""

usage() {
    cat <<'USAGE'
Trigger a Jenkins job on the instance from JENKINS_URL.

Environment:
  JENKINS_URL     Jenkins root URL (required)
  JENKINS_USER    Jenkins username (required)
  JENKINS_TOKEN   Jenkins API token (required)
  JENKINS_JOB     Jenkins job to trigger (default: seed)
  WAIT_BUILD      true to wait for the triggered build to finish
  TIMEOUT_SEC     max wait seconds when WAIT_BUILD=true (default 1800)
  POLL_INTERVAL_SEC poll interval (default 10)

Optional prow post-submit params are forwarded automatically from the
environment (BUILD_ID, PROW_JOB_ID, JOB_SPEC).
USAGE
}

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }

trim_trailing_slash() {
    local s="$1"
    while [[ "$s" == */ ]]; do s="${s%/}"; done
    printf '%s' "$s"
}

get_crumb() {
    local url="$1" field="" value=""
    local json
    json="$(curl -sS -u "${JENKINS_USER}:${JENKINS_TOKEN}" -c "$COOKIE_JAR" "${url}/crumbIssuer/api/json" 2>/dev/null || true)"
    if [[ -n "$json" ]]; then
        field="$(jq -r '.crumbRequestField // empty' <<<"$json" 2>/dev/null)"
        value="$(jq -r '.crumb // empty' <<<"$json" 2>/dev/null)"
    fi
    if [[ -n "$field" && -n "$value" ]]; then
        printf '%s: %s' "$field" "$value"
    fi
}

build_trigger_args() {
    local -a args=()
    local p
    for p in BUILD_ID PROW_JOB_ID JOB_SPEC; do
        if [[ -n "${!p:-}" ]]; then
            args+=(--data-urlencode "${p}=${!p}")
        fi
    done
    printf '%s' "${args[*]:-}"
}

wait_queue_to_build_url() {
    local queue_url="$1" started now
    started="$(date +%s)"
    while true; do
        now="$(date +%s)"
        if (( now - started > TIMEOUT_SEC )); then
            log "timeout waiting queue item executable URL: ${queue_url}"
            return 1
        fi
        local body
        body="$(curl -sS -u "${JENKINS_USER}:${JENKINS_TOKEN}" -b "$COOKIE_JAR" "${queue_url}/api/json" 2>/dev/null || true)"
        local cancelled build_url
        cancelled="$(jq -r '.cancelled // false' <<<"$body" 2>/dev/null)"
        build_url="$(jq -r '.executable.url // empty' <<<"$body" 2>/dev/null)"
        if [[ "$cancelled" == "true" ]]; then
            log "queue item cancelled: ${queue_url}"
            return 1
        fi
        if [[ -n "$build_url" ]]; then
            printf '%s' "$(trim_trailing_slash "$build_url")"
            return 0
        fi
        sleep "$POLL_INTERVAL_SEC"
    done
}

wait_build_result() {
    local build_url="$1" started now
    started="$(date +%s)"
    while true; do
        now="$(date +%s)"
        if (( now - started > TIMEOUT_SEC )); then
            log "timeout waiting build result: ${build_url}"
            return 1
        fi
        local body
        body="$(curl -sS -u "${JENKINS_USER}:${JENKINS_TOKEN}" -b "$COOKIE_JAR" "${build_url}/api/json?tree=building,result,url" 2>/dev/null || true)"
        local building result
        building="$(jq -r '.building // false' <<<"$body" 2>/dev/null)"
        result="$(jq -r '.result // empty' <<<"$body" 2>/dev/null)"
        if [[ "$building" != "true" && -n "$result" && "$result" != "null" ]]; then
            log "build finished: ${build_url} => ${result}"
            [[ "$result" == "SUCCESS" ]] && return 0
            return 1
        fi
        sleep "$POLL_INTERVAL_SEC"
    done
}

main() {
    [[ -n "$JENKINS_URL" ]] || { usage; exit 1; }
    [[ -n "$JENKINS_USER" && -n "$JENKINS_TOKEN" ]] || { usage; exit 1; }
    command -v curl >/dev/null || { log "missing curl"; exit 1; }
    command -v jq >/dev/null || { log "missing jq"; exit 1; }

    JENKINS_URL="$(trim_trailing_slash "$JENKINS_URL")"
    local job_url="${JENKINS_URL}/job/${JENKINS_JOB}"
    local crumb header_args=()
    COOKIE_JAR="$(mktemp)"
    trap 'rm -f "$COOKIE_JAR"' EXIT
    crumb="$(get_crumb "$JENKINS_URL")"
    [[ -n "$crumb" ]] && header_args=(-H "$crumb")

    # shellcheck disable=SC2206
    local -a args=($(build_trigger_args))
    local endpoint="build"
    if (( ${#args[@]} > 0 )); then
        endpoint="buildWithParameters"
    fi

    log "triggering ${JENKINS_URL} job ${JENKINS_JOB} (endpoint ${endpoint})"
    local headers status loc
    headers="$(mktemp)"
    status="$(curl -sS -u "${JENKINS_USER}:${JENKINS_TOKEN}" -b "$COOKIE_JAR" "${header_args[@]}" -o /dev/null -D "$headers" \
        -w '%{http_code}' -X POST "${args[@]}" "${job_url}/${endpoint}" || true)"
    loc="$(awk -F': ' 'tolower($1)=="location" {gsub("\r", "", $2); print $2; exit}' "$headers")"
    rm -f "$headers"

    if ! { [[ "$status" == "200" || "$status" == "201" ]]; } || [[ -z "$loc" ]]; then
        log "trigger failed for ${job_url}: HTTP ${status}"
        return 1
    fi

    local queue_url build_url
    queue_url="$(trim_trailing_slash "$loc")"
    log "queued: ${queue_url}"
    if ! build_url="$(wait_queue_to_build_url "$queue_url")"; then
        return 1
    fi
    log "build assigned: ${build_url}"

    if [[ "$WAIT_BUILD" == "true" ]]; then
        wait_build_result "$build_url" || return 1
    fi
    log "ok: ${build_url}"
    return 0
}

main "$@"
