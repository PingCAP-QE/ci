#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'USAGE'
Replay a Prow job locally.

Modes:
  1) pod      : Create a temporary pod and run extracted command directly.
  2) prowjob  : Create a ProwJob CR in prow control namespace and wait for result.
  3) auto     : Prefer prowjob replay with historical PR context; fallback to pod.

Examples:
  # pod mode (legacy local replay)
  .ci/replay-prow-job.sh \
    --mode pod \
    --config prow-jobs/pingcap-inc/tiflash-scripts/presubmits.yaml \
    --job-name pull-test \
    --repo pingcap-inc/tiflash-scripts

  # prowjob mode (recommended)
  .ci/replay-prow-job.sh \
    --mode prowjob \
    --config prow-jobs/pingcap-inc/tiflash-scripts/presubmits.yaml \
    --job-name pull-test \
    --repo pingcap-inc/tiflash-scripts \
    --set-env PULL_BASE_REF=master

  # auto replay changed native prow jobs in current PR/worktree
  .ci/replay-prow-job.sh \
    --auto-changed \
    --base-sha "$PULL_BASE_SHA" \
    --head-sha "$PULL_PULL_SHA" \
    --mode pod \
    --namespace prow-test-pods

Required:
  --config <path>              Prow job yaml file. (not required in --auto-changed mode)
  --job-name <name>            Job name in prow config. (not required in --auto-changed mode)

Common options:
  --auto-changed               Auto-detect changed native prow jobs from git diff and replay them in batch.
  --base-sha <sha>             Base SHA for --auto-changed. Default: $PULL_BASE_SHA or merge-base(origin/main, HEAD).
  --head-sha <sha>             Head SHA for --auto-changed. Default: $PULL_PULL_SHA or HEAD.
  --max-replays <count>        Max replay jobs in --auto-changed mode. Default: 3.
  --max-jobs-per-file <count>  Fallback max selected jobs per changed file. Default: 3.
  --mode <pod|prowjob|auto>    Default: pod.
  --job-type <type>            auto|presubmit|postsubmit|periodic. Default: auto.
  --repo <org/repo>            Required for ambiguous presubmit/postsubmit selection.
  --container <name>           Container name in spec.containers. Default: first.
  --context <ctx>              kubectl context.
  --set-env KEY=VALUE          Override logical env (repeatable), e.g. PULL_BASE_REF.
  --run-timeout <s>            Timeout for full replay/wait. Default: 10800.
  --poll-interval <s>          Poll interval for prowjob status. Default: 15.
  --dry-run                    Print resolved content only.
  --verbose                    Print detailed logs (mainly for --auto-changed mode).

Pod mode options:
  --checkout-mode <github|workspace>
                              Source checkout method for pod mode. Default: github.
  --git-url <url>             Git URL used when checkout-mode=github.
  --git-ref <ref|sha>         Git ref used when checkout-mode=github. Default: PULL_PULL_SHA or PULL_BASE_REF.
  --git-clone-depth <n>       Clone depth for github mode. Use 0 for full clone. Default: 1.
  --git-submodules            Run `git submodule update --init --recursive` in pod after github clone.
  --git-token-secret <name:key>
                              Optional secret to inject REPLAY_GIT_TOKEN in pod for private repo clone.
  --pod-workdir <path>        Workdir inside pod for github clone. Default: /workspace/src.
  --workspace <dir>            Local workspace copied to pod /workspace. Default: cwd.
  --namespace <ns>             Namespace for temporary pod. Default: default.
  --pod-name <name>            Fixed pod name.
  --pod-ready-timeout <s>      Pod ready timeout. Default: 300.
  --keep-pod                   Keep pod after finish.

ProwJob mode options:
  --prowjob-namespace <ns>     Namespace where ProwJob CR is created. Default: apps.
  --run-namespace <ns>         Namespace where plank schedules build pod. Default: prow-test-pods.
  --deck-base-url <url>        Deck base URL. Default: https://prow.tidb.net.
  --delete-prowjob             Delete ProwJob after finish.

  -h, --help                   Show help.

Notes:
  - env.valueFrom entries in job container are skipped in pod mode extraction.
  - For presubmit ProwJob, if both PULL_NUMBER and PULL_PULL_SHA are provided via --set-env,
    they are injected into refs.pulls to run against that PR commit.
  - In auto mode, presubmit/postsubmit jobs try to reuse latest historical PR context
    from ProwJob CRs (same job + repo). If none is found, it falls back to pod replay.
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

abs_path() {
    local p="$1"
    if [[ "$p" = /* ]]; then
        printf '%s' "$p"
    else
        printf '%s/%s' "$(pwd)" "$p"
    fi
}

get_override() {
    local key="$1"
    local def="$2"
    local v="$def"
    local pair=""
    local k=""
    local value=""
    for pair in "${EXTRA_ENVS[@]:-}"; do
        [[ -n "$pair" ]] || continue
        [[ "$pair" == *=* ]] || continue
        k="${pair%%=*}"
        value="${pair#*=}"
        if [[ "$k" == "$key" ]]; then
            v="$value"
        fi
    done
    printf '%s' "$v"
}

append_override_if_missing() {
    local key="$1"
    local value="$2"
    [[ -n "$key" ]] || return 1
    [[ -n "$value" ]] || return 1
    if extra_env_has_key "$key"; then
        return 0
    fi
    EXTRA_ENVS+=("${key}=${value}")
    return 0
}

KUBECTL_OPTS=()
run_kubectl() {
    if [[ "${#KUBECTL_OPTS[@]}" -gt 0 ]]; then
        kubectl "${KUBECTL_OPTS[@]}" "$@"
    else
        kubectl "$@"
    fi
}

ensure_kubectl_auth() {
    if kubectl "${KUBECTL_OPTS[@]}" version --request-timeout=5s >/dev/null 2>&1; then
        return 0
    fi
    log "kubectl API check failed with current config, trying in-cluster serviceaccount auth"

    local sa_dir="/var/run/secrets/kubernetes.io/serviceaccount"
    local sa_token="${sa_dir}/token"
    local sa_ca="${sa_dir}/ca.crt"
    local sa_ns="${sa_dir}/namespace"

    if [[ -z "${KUBERNETES_SERVICE_HOST:-}" || ! -f "${sa_token}" || ! -f "${sa_ca}" ]]; then
        log "in-cluster serviceaccount files are unavailable, keep current kubectl config"
        return 0
    fi

    local kube_ns kube_server kubeconfig_file
    kube_ns="$(cat "${sa_ns}" 2>/dev/null || echo default)"
    kube_server="https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT_HTTPS:-443}"
    kubeconfig_file="$(mktemp /tmp/replay-prow-incluster-kubeconfig.XXXXXX)"

    kubectl config --kubeconfig "${kubeconfig_file}" set-cluster in-cluster \
        --server="${kube_server}" \
        --certificate-authority="${sa_ca}" \
        --embed-certs=true >/dev/null
    kubectl config --kubeconfig "${kubeconfig_file}" set-credentials in-cluster \
        --token="$(cat "${sa_token}")" >/dev/null
    kubectl config --kubeconfig "${kubeconfig_file}" set-context in-cluster \
        --cluster=in-cluster \
        --user=in-cluster \
        --namespace="${kube_ns}" >/dev/null
    kubectl config --kubeconfig "${kubeconfig_file}" use-context in-cluster >/dev/null

    KUBECTL_OPTS+=(--kubeconfig "${kubeconfig_file}")
    log "kubectl: switched to in-cluster serviceaccount context (namespace=${kube_ns})"

    if ! kubectl "${KUBECTL_OPTS[@]}" version --request-timeout=5s >/dev/null 2>&1; then
        log "kubectl API check still failed after in-cluster auth bootstrap"
    fi
}

to_lower() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

detect_job_type_from_file() {
    local file="$1"
    local base
    base="$(basename "$file")"
    case "$base" in
        *presubmit*.yml|*presubmit*.yaml) echo "presubmit" ;;
        *postsubmit*.yml|*postsubmit*.yaml) echo "postsubmit" ;;
        *periodic*.yml|*periodic*.yaml) echo "periodic" ;;
        *) echo "auto" ;;
    esac
}

detect_repo_from_file() {
    local file="$1"
    local rel parts
    rel="${file#prow-jobs/}"
    IFS='/' read -r -a parts <<<"${rel}"
    if (( ${#parts[@]} >= 3 )); then
        echo "${parts[0]}/${parts[1]}"
    else
        echo ""
    fi
}

infer_base_ref_from_file() {
    local repo="$1"
    local file="$2"
    local base stem candidate

    if [[ "$(to_lower "$repo")" == "pingcap-qe/ci" ]]; then
        echo "main"
        return 0
    fi

    base="$(basename "$file")"
    stem="${base%.*}"

    if [[ "${stem}" == release-* ]]; then
        candidate="${stem}"
        candidate="${candidate%%-presubmit*}"
        candidate="${candidate%%-postsubmit*}"
        candidate="${candidate%%-periodic*}"
        candidate="${candidate%-latest}"
        if [[ "${candidate}" == release-* ]]; then
            echo "${candidate}"
            return 0
        fi
    fi

    echo "master"
}

default_submodule_skip_paths_for_repo() {
    local repo="$1"
    case "$repo" in
        pingcap/tiflash|pingcap-inc/tiflash-scripts)
            echo "contrib/tiflash-proxy-next-gen"
            ;;
        *)
            echo ""
            ;;
    esac
}

extra_env_has_key() {
    local target_key="$1"
    local pair k
    for pair in "${EXTRA_ENVS[@]:-}"; do
        [[ -n "$pair" ]] || continue
        [[ "$pair" == *=* ]] || continue
        k="${pair%%=*}"
        if [[ "$k" == "$target_key" ]]; then
            return 0
        fi
    done
    return 1
}

has_pull_context_override() {
    local pull_number pull_sha
    pull_number="$(get_override PULL_NUMBER '')"
    pull_sha="$(get_override PULL_PULL_SHA '')"
    [[ -n "${pull_number}" && -n "${pull_sha}" ]]
}

resolve_latest_pull_context_from_history() {
    [[ -n "${REPO_SELECTED:-}" ]] || return 1
    [[ "${REPO_SELECTED}" == */* ]] || return 1

    local org repo selector json line
    org="${REPO_SELECTED%%/*}"
    repo="${REPO_SELECTED##*/}"
    selector="prow.k8s.io/job=${JOB_NAME},prow.k8s.io/refs.org=${org},prow.k8s.io/refs.repo=${repo}"

    json="$(run_kubectl --namespace "${PROWJOB_NAMESPACE}" get prowjobs -l "${selector}" -o json 2>/dev/null || true)"
    [[ -n "${json}" ]] || return 1
    line="$(jq -r --arg default_base "$(get_override PULL_BASE_REF master)" '
      .items
      | map(select(
          ((.status.state // "" | ascii_downcase) == "success") and
          (.spec.refs.org // "") != "" and
          (.spec.refs.repo // "") != "" and
          ((.spec.refs.pulls | type) == "array") and
          (.spec.refs.pulls | length) > 0 and
          ((.spec.refs.pulls[0].number // "") != "") and
          ((.spec.refs.pulls[0].sha // "") != "")
        ))
      | sort_by(.metadata.creationTimestamp // "")
      | reverse
      | .[0] as $x
      | if $x == null then
          empty
        else
          [
            ($x.spec.refs.base_ref // $default_base),
            ($x.spec.refs.pulls[0].number | tostring),
            ($x.spec.refs.pulls[0].sha | tostring),
            ($x.metadata.name // "")
          ] | @tsv
        end
    ' <<<"${json}")"
    [[ -n "${line}" ]] || return 1
    printf '%s\n' "${line}"
    return 0
}

resolve_job_agent() {
    local file="$1"
    local repo="$2"
    local kind="$3"
    local name="$4"

    ruby -ryaml -e '
file = ARGV[0]
repo = ARGV[1]
kind = ARGV[2]
name = ARGV[3]

cfg = YAML.safe_load(
  File.read(file),
  permitted_classes: [],
  permitted_symbols: [],
  aliases: true
) || {}
jobs = []

case kind
when "presubmit"
  h = cfg["presubmits"] || {}
  if !repo.empty?
    arr = h[repo] || []
    arr.each { |j| jobs << j if j.is_a?(Hash) }
  else
    h.each_value do |arr|
      next unless arr.is_a?(Array)
      arr.each { |j| jobs << j if j.is_a?(Hash) }
    end
  end
when "postsubmit"
  h = cfg["postsubmits"] || {}
  if !repo.empty?
    arr = h[repo] || []
    arr.each { |j| jobs << j if j.is_a?(Hash) }
  else
    h.each_value do |arr|
      next unless arr.is_a?(Array)
      arr.each { |j| jobs << j if j.is_a?(Hash) }
    end
  end
when "periodic"
  arr = cfg["periodics"] || []
  arr.each { |j| jobs << j if j.is_a?(Hash) }
else
  puts ""
  exit 0
end

job = jobs.find { |j| j["name"].to_s == name.to_s }
if job.nil?
  puts ""
else
  agent = job["agent"]
  puts(agent.nil? || agent.to_s.empty? ? "kubernetes" : agent.to_s)
end
' "$file" "$repo" "$kind" "$name"
}

collect_changed_files() {
    local base_sha="$1"
    local head_sha="$2"
    git diff --name-only --diff-filter=ACMRT "${base_sha}...${head_sha}" -- \
        | awk '/^prow-jobs\/.*\.(yaml|yml)$/ && !/kustomization\.yaml$/ {print $0}' \
        | sort -u
}

is_replayable_prow_config_file() {
    local file="$1"
    grep -Eq '^[[:space:]]*(presubmits|postsubmits|periodics|batch_presubmits):[[:space:]]*$' "${file}"
}

collect_changed_lines() {
    local base_sha="$1"
    local head_sha="$2"
    local file="$3"

    git diff -U0 "${base_sha}...${head_sha}" -- "${file}" | while IFS= read -r line; do
        [[ "$line" == @@* ]] || continue
        if [[ "$line" =~ ^@@[[:space:]]-[0-9]+(,[0-9]+)?[[:space:]]\+([0-9]+)(,([0-9]+))?[[:space:]]@@ ]]; then
            local start count i
            start="${BASH_REMATCH[2]}"
            count="${BASH_REMATCH[4]:-1}"
            if (( count == 0 )); then
                echo "${start}"
            else
                for (( i = 0; i < count; i++ )); do
                    echo $((start + i))
                done
            fi
        fi
    done | sort -nu
}

extract_job_ranges() {
    local file="$1"
    awk '
function trim_right(s) {
  sub(/[[:space:]]+$/, "", s)
  return s
}
function repeat_space(n,    out, i) {
  out = ""
  for (i = 0; i < n; i++) {
    out = out " "
  }
  return out
}
function flush(next_start, end_line) {
  if (!in_item) return
  if (next_start > 0) {
    end_line = next_start - 1
  } else {
    end_line = NR
  }
  if (name != "") {
    print start, end_line, name, is_jenkins
  }
}
{
  line = $0
  if (line ~ /^(  |    )-[[:space:]]+/) {
    flush(NR)
    in_item = 1
    start = NR
    name = ""
    is_jenkins = 0
    indent = (substr(line, 1, 4) == "    " ? 4 : 2)

    direct = line
    sub(/^(  |    )-[[:space:]]*/, "", direct)
    if (direct ~ /^name:[[:space:]]*/) {
      sub(/^name:[[:space:]]*/, "", direct)
      name = trim_right(direct)
    }
    next
  }

  if (!in_item) next

  name_pat = "^" repeat_space(indent + 2) "name:[[:space:]]*"
  if (name == "" && line ~ name_pat) {
    value = line
    sub(name_pat, "", value)
    name = trim_right(value)
  }

  jenkins_pat = "^" repeat_space(indent + 2) "agent:[[:space:]]*jenkins([[:space:]]*#.*)?$"
  if (line ~ jenkins_pat) {
    is_jenkins = 1
  }
}
END {
  flush(0)
}
' "${file}"
}

select_jobs_for_file() {
    local file="$1"
    local base_sha="$2"
    local head_sha="$3"
    local max_jobs_per_file="$4"
    local changed_lines_file ranges_file selected_file
    local start end name is_jenkins fallback_count

    changed_lines_file="$(mktemp)"
    ranges_file="$(mktemp)"
    selected_file="$(mktemp)"
    collect_changed_lines "${base_sha}" "${head_sha}" "${file}" > "${changed_lines_file}"
    extract_job_ranges "${file}" > "${ranges_file}"

    if [[ ! -s "${ranges_file}" ]]; then
        rm -f "${changed_lines_file}" "${ranges_file}" "${selected_file}"
        return 0
    fi

    while read -r start end name is_jenkins; do
        [[ -n "${name}" ]] || continue
        [[ "${is_jenkins}" == "1" ]] && continue
        if awk -v s="${start}" -v e="${end}" '($1 >= s && $1 <= e) { found=1; exit } END { exit(found ? 0 : 1) }' "${changed_lines_file}"; then
            echo "${name}" >> "${selected_file}"
        fi
    done < "${ranges_file}"

    if [[ ! -s "${selected_file}" ]]; then
        fallback_count=0
        while read -r start end name is_jenkins; do
            [[ -n "${name}" ]] || continue
            [[ "${is_jenkins}" == "1" ]] && continue
            echo "${name}" >> "${selected_file}"
            fallback_count=$((fallback_count + 1))
            if (( fallback_count >= max_jobs_per_file )); then
                break
            fi
        done < "${ranges_file}"
        vlog "file=${file} no direct changed job matched; fallback select first ${fallback_count} native jobs"
    fi

    if [[ -s "${selected_file}" ]]; then
        sort -u "${selected_file}"
    fi

    rm -f "${changed_lines_file}" "${ranges_file}" "${selected_file}"
}

MODE="pod"
CONFIG=""
JOB_NAME=""
JOB_TYPE="auto"
REPO=""
CONTAINER_NAME=""
RUN_TIMEOUT="10800"
POLL_INTERVAL="15"
DRY_RUN="false"
VERBOSE="false"
AUTO_CHANGED="false"
BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-}"
MAX_REPLAYS="3"
MAX_JOBS_PER_FILE="3"

# pod mode
CHECKOUT_MODE="github"
GIT_URL=""
GIT_REF=""
GIT_CLONE_DEPTH="1"
GIT_SUBMODULES="false"
GIT_TOKEN_SECRET=""
POD_WORKDIR="/workspace/src"
WORKSPACE="$(pwd)"
NAMESPACE="default"
POD_NAME=""
POD_READY_TIMEOUT="300"
KEEP_POD="false"
POD_NAME_ACTIVE=""
POD_ENV_FILE_ACTIVE=""
POD_RUN_FILE_ACTIVE=""
POD_SPEC_FILE_ACTIVE=""

# prowjob mode
PROWJOB_NAMESPACE="apps"
RUN_NAMESPACE="prow-test-pods"
DECK_BASE_URL="https://prow.tidb.net"
DELETE_PROWJOB="false"

EXTRA_ENVS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)
            MODE="$2"
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
        --max-replays)
            MAX_REPLAYS="$2"
            shift 2
            ;;
        --max-jobs-per-file)
            MAX_JOBS_PER_FILE="$2"
            shift 2
            ;;
        --config)
            CONFIG="$2"
            shift 2
            ;;
        --job-name)
            JOB_NAME="$2"
            shift 2
            ;;
        --job-type)
            JOB_TYPE="$2"
            shift 2
            ;;
        --repo)
            REPO="$2"
            shift 2
            ;;
        --container)
            CONTAINER_NAME="$2"
            shift 2
            ;;
        --workspace)
            WORKSPACE="$2"
            shift 2
            ;;
        --checkout-mode)
            CHECKOUT_MODE="$2"
            shift 2
            ;;
        --git-url)
            GIT_URL="$2"
            shift 2
            ;;
        --git-ref)
            GIT_REF="$2"
            shift 2
            ;;
        --git-clone-depth)
            GIT_CLONE_DEPTH="$2"
            shift 2
            ;;
        --git-submodules)
            GIT_SUBMODULES="true"
            shift
            ;;
        --git-token-secret)
            GIT_TOKEN_SECRET="$2"
            shift 2
            ;;
        --pod-workdir)
            POD_WORKDIR="$2"
            shift 2
            ;;
        --namespace)
            NAMESPACE="$2"
            shift 2
            ;;
        --context)
            CONTEXT="$2"
            shift 2
            ;;
        --pod-name)
            POD_NAME="$2"
            shift 2
            ;;
        --pod-ready-timeout)
            POD_READY_TIMEOUT="$2"
            shift 2
            ;;
        --run-timeout)
            RUN_TIMEOUT="$2"
            shift 2
            ;;
        --poll-interval)
            POLL_INTERVAL="$2"
            shift 2
            ;;
        --set-env)
            EXTRA_ENVS+=("$2")
            shift 2
            ;;
        --keep-pod)
            KEEP_POD="true"
            shift
            ;;
        --prowjob-namespace)
            PROWJOB_NAMESPACE="$2"
            shift 2
            ;;
        --run-namespace)
            RUN_NAMESPACE="$2"
            shift 2
            ;;
        --deck-base-url)
            DECK_BASE_URL="$2"
            shift 2
            ;;
        --delete-prowjob)
            DELETE_PROWJOB="true"
            shift
            ;;
        --dry-run)
            DRY_RUN="true"
            shift
            ;;
        --verbose)
            VERBOSE="true"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fatal "unknown option: $1"
            ;;
    esac
done

auto_replay_changed_jobs() {
    require_bin git
    require_bin bash
    require_bin sort
    require_bin ruby

    local head_sha="${HEAD_SHA:-}"
    local base_sha="${BASE_SHA:-}"
    local script_path
    local changed_files_file
    local targets_file
    local selected_jobs_file
    local target_count
    local failures
    local file job_name job_type repo base_ref agent

    if [[ -z "${head_sha}" ]]; then
        head_sha="$(git rev-parse HEAD)"
    fi
    if [[ -z "${base_sha}" ]]; then
        if git rev-parse --verify origin/main >/dev/null 2>&1; then
            base_sha="$(git merge-base origin/main "${head_sha}")"
        else
            fatal "base sha is empty, set --base-sha or PULL_BASE_SHA"
        fi
    fi

    git cat-file -e "${base_sha}^{commit}" 2>/dev/null || fatal "base sha not found in local git: ${base_sha}"
    git cat-file -e "${head_sha}^{commit}" 2>/dev/null || fatal "head sha not found in local git: ${head_sha}"

    script_path="$(abs_path "$0")"

    changed_files_file="$(mktemp)"
    targets_file="$(mktemp)"
    collect_changed_files "${base_sha}" "${head_sha}" > "${changed_files_file}"

    log "detect changed prow-jobs yaml files: base=${base_sha} head=${head_sha}"
    if [[ ! -s "${changed_files_file}" ]]; then
        log "no changed prow-jobs yaml files, skip replay"
        rm -f "${changed_files_file}" "${targets_file}"
        return 0
    fi

    while IFS= read -r file; do
        [[ -n "${file}" ]] || continue
        [[ -f "${file}" ]] || continue
        if ! is_replayable_prow_config_file "${file}"; then
            vlog "skip non-job prow file: ${file}"
            continue
        fi

        vlog "collect targets from file=${file}"
        job_type="$(detect_job_type_from_file "${file}")"
        repo="$(detect_repo_from_file "${file}")"
        base_ref="$(infer_base_ref_from_file "${repo}" "${file}")"

        selected_jobs_file="$(mktemp)"
        select_jobs_for_file "${file}" "${base_sha}" "${head_sha}" "${MAX_JOBS_PER_FILE}" > "${selected_jobs_file}"
        if [[ ! -s "${selected_jobs_file}" ]]; then
            vlog "file=${file} no native jobs selected"
            rm -f "${selected_jobs_file}"
            continue
        fi

        while IFS= read -r job_name; do
            [[ -n "${job_name}" ]] || continue
            # avoid recursive replay of this automation job itself.
            if [[ "${job_name}" == "pull-replay-prow-jobs" ]]; then
                continue
            fi

            agent="$(resolve_job_agent "${file}" "${repo}" "${job_type}" "${job_name}")"
            if [[ -z "${agent}" ]]; then
                vlog "skip unresolved job (cannot resolve agent): file=${file} job=${job_name} type=${job_type} repo=${repo:-<auto>}"
                continue
            fi
            if [[ "${agent}" == "jenkins" ]]; then
                vlog "skip jenkins job: file=${file} job=${job_name}"
                continue
            fi

            echo "${file}|${job_name}|${job_type}|${repo}|${base_ref}" >> "${targets_file}"
        done < "${selected_jobs_file}"

        rm -f "${selected_jobs_file}"
    done < "${changed_files_file}"

    if [[ -s "${targets_file}" ]]; then
        sort -u "${targets_file}" -o "${targets_file}"
    fi

    target_count="$(wc -l < "${targets_file}" | tr -d ' ')"
    if [[ "${target_count}" == "0" ]]; then
        log "no replay targets selected from changed files"
        rm -f "${changed_files_file}" "${targets_file}"
        return 0
    fi

    if (( target_count > MAX_REPLAYS )); then
        log "selected ${target_count} jobs, truncate to max-replays=${MAX_REPLAYS}"
        head -n "${MAX_REPLAYS}" "${targets_file}" > "${targets_file}.limited"
        mv "${targets_file}.limited" "${targets_file}"
        target_count="${MAX_REPLAYS}"
    fi

    log "selected replay targets: ${target_count}"
    while IFS='|' read -r file job_name job_type repo base_ref; do
        log "target: file=${file} job=${job_name} type=${job_type} repo=${repo:-<auto>} base_ref=${base_ref}"
    done < "${targets_file}"

    failures=0
    while IFS='|' read -r file job_name job_type repo base_ref; do
        local cmd=()
        local pair=""
        local skip_paths=""

        cmd=(bash "${script_path}"
            --mode "${MODE}"
            --config "${file}"
            --job-name "${job_name}"
            --job-type "${job_type}"
            --run-timeout "${RUN_TIMEOUT}"
            --poll-interval "${POLL_INTERVAL}"
        )

        if [[ -n "${repo}" ]]; then
            cmd+=(--repo "${repo}")
        fi
        if [[ -n "${CONTEXT:-}" ]]; then
            cmd+=(--context "${CONTEXT}")
        fi
        if [[ "${VERBOSE}" == "true" ]]; then
            cmd+=(--verbose)
        fi
        if [[ "${DRY_RUN}" == "true" ]]; then
            cmd+=(--dry-run)
        fi

        for pair in "${EXTRA_ENVS[@]:-}"; do
            [[ -n "${pair}" ]] || continue
            cmd+=(--set-env "${pair}")
        done
        cmd+=(--set-env "PULL_BASE_REF=${base_ref}")
        if extra_env_has_key "REPLAY_GIT_SUBMODULE_SKIP_PATHS"; then
            :
        elif [[ -n "${REPLAY_SUBMODULE_SKIP_PATHS:-}" ]]; then
            skip_paths="${REPLAY_SUBMODULE_SKIP_PATHS}"
        else
            skip_paths="$(default_submodule_skip_paths_for_repo "${repo}")"
        fi
        if [[ -n "${skip_paths}" ]]; then
            vlog "apply submodule skip paths for repo=${repo}: ${skip_paths}"
            cmd+=(--set-env "REPLAY_GIT_SUBMODULE_SKIP_PATHS=${skip_paths}")
        fi

        if [[ "${MODE}" == "pod" ]]; then
            cmd+=(
                --checkout-mode "${CHECKOUT_MODE}"
                --git-clone-depth "${GIT_CLONE_DEPTH}"
                --pod-workdir "${POD_WORKDIR}"
                --workspace "${WORKSPACE}"
                --namespace "${NAMESPACE}"
                --pod-ready-timeout "${POD_READY_TIMEOUT}"
            )
            if [[ "${GIT_SUBMODULES}" == "true" ]]; then
                cmd+=(--git-submodules)
            fi
            if [[ -n "${GIT_URL}" ]]; then
                cmd+=(--git-url "${GIT_URL}")
            fi
            if [[ -n "${GIT_REF}" ]]; then
                cmd+=(--git-ref "${GIT_REF}")
            fi
            if [[ -n "${GIT_TOKEN_SECRET}" ]]; then
                cmd+=(--git-token-secret "${GIT_TOKEN_SECRET}")
            fi
            if [[ "${KEEP_POD}" == "true" ]]; then
                cmd+=(--keep-pod)
            fi
        else
            cmd+=(
                --prowjob-namespace "${PROWJOB_NAMESPACE}"
                --run-namespace "${RUN_NAMESPACE}"
                --deck-base-url "${DECK_BASE_URL}"
            )
            if [[ "${DELETE_PROWJOB}" == "true" ]]; then
                cmd+=(--delete-prowjob)
            fi
        fi

        log "replay start: job=${job_name} file=${file}"
        if "${cmd[@]}"; then
            log "replay pass: job=${job_name}"
        else
            log "replay fail: job=${job_name}"
            ((failures += 1))
        fi
    done < "${targets_file}"

    rm -f "${changed_files_file}" "${targets_file}"

    if (( failures > 0 )); then
        fatal "replay finished with ${failures} failure(s)"
    fi

    log "replay finished successfully"
}

[[ "$MODE" == "pod" || "$MODE" == "prowjob" || "$MODE" == "auto" ]] || fatal "--mode must be pod|prowjob|auto"
[[ "$CHECKOUT_MODE" == "github" || "$CHECKOUT_MODE" == "workspace" ]] || fatal "--checkout-mode must be github|workspace"
[[ "$RUN_TIMEOUT" =~ ^[0-9]+$ ]] || fatal "--run-timeout must be an integer number of seconds"
(( RUN_TIMEOUT > 0 )) || fatal "--run-timeout must be greater than 0"
if [[ "${AUTO_CHANGED}" == "true" ]]; then
    auto_replay_changed_jobs
    exit 0
fi
[[ -n "$CONFIG" ]] || fatal "--config is required"
[[ -n "$JOB_NAME" ]] || fatal "--job-name is required"
[[ "$JOB_TYPE" == "auto" || "$JOB_TYPE" == "presubmit" || "$JOB_TYPE" == "postsubmit" || "$JOB_TYPE" == "periodic" ]] || fatal "--job-type must be auto|presubmit|postsubmit|periodic"

CONFIG="$(abs_path "$CONFIG")"
WORKSPACE="$(abs_path "$WORKSPACE")"

[[ -f "$CONFIG" ]] || fatal "config not found: $CONFIG"
[[ -d "$WORKSPACE" ]] || fatal "workspace dir not found: $WORKSPACE"

require_bin kubectl
require_bin ruby
require_bin jq
require_bin tar

CONTEXT="${CONTEXT:-}"
if [[ -n "$CONTEXT" ]]; then
    KUBECTL_OPTS+=(--context "$CONTEXT")
fi

ensure_kubectl_auth

extract_job() {
    ruby - "$CONFIG" "$JOB_NAME" "$JOB_TYPE" "$REPO" "$CONTAINER_NAME" <<'RUBY'
require 'yaml'
require 'json'

config = ARGV[0]
job_name = ARGV[1]
job_type = ARGV[2]
repo_filter = ARGV[3]
container_name = ARGV[4]

obj = YAML.safe_load(
  File.read(config),
  permitted_classes: [],
  permitted_symbols: [],
  aliases: true
)

candidates = []

if (job_type == 'auto' || job_type == 'periodic') && obj.is_a?(Hash) && obj['periodics'].is_a?(Array)
  obj['periodics'].each do |job|
    next unless job.is_a?(Hash)
    next unless job['name'].to_s == job_name
    candidates << {'kind' => 'periodic', 'repo' => nil, 'job' => job}
  end
end

if (job_type == 'auto' || job_type == 'presubmit') && obj.is_a?(Hash) && obj['presubmits'].is_a?(Hash)
  obj['presubmits'].each do |repo, jobs|
    next unless jobs.is_a?(Array)
    jobs.each do |job|
      next unless job.is_a?(Hash)
      next unless job['name'].to_s == job_name
      next if !repo_filter.to_s.empty? && repo.to_s != repo_filter
      candidates << {'kind' => 'presubmit', 'repo' => repo.to_s, 'job' => job}
    end
  end
end

if (job_type == 'auto' || job_type == 'postsubmit') && obj.is_a?(Hash) && obj['postsubmits'].is_a?(Hash)
  obj['postsubmits'].each do |repo, jobs|
    next unless jobs.is_a?(Array)
    jobs.each do |job|
      next unless job.is_a?(Hash)
      next unless job['name'].to_s == job_name
      next if !repo_filter.to_s.empty? && repo.to_s != repo_filter
      candidates << {'kind' => 'postsubmit', 'repo' => repo.to_s, 'job' => job}
    end
  end
end

if candidates.empty?
  STDERR.puts "No matching job found: name=#{job_name}, type=#{job_type}, repo=#{repo_filter.empty? ? '(auto)' : repo_filter}"
  exit 2
end

if candidates.size > 1
  summary = candidates.map { |c| "#{c['kind']}:#{c['repo'] || '-'}" }.join(', ')
  STDERR.puts "Matched multiple jobs for '#{job_name}': #{summary}. Please set --job-type and/or --repo."
  exit 3
end

selected = candidates.first
job = selected['job']
containers = job.dig('spec', 'containers')
unless containers.is_a?(Array) && !containers.empty?
  STDERR.puts "Job '#{job_name}' has no spec.containers"
  exit 4
end

container = nil
if container_name && !container_name.empty?
  container = containers.find { |c| c.is_a?(Hash) && c['name'].to_s == container_name }
  if container.nil?
    STDERR.puts "Container '#{container_name}' not found in job '#{job_name}'"
    exit 5
  end
else
  container = containers.first
end

env_map = {}
skipped_value_from = []
(container['env'] || []).each do |e|
  next unless e.is_a?(Hash)
  key = e['name'].to_s
  next if key.empty?
  if e.key?('value')
    env_map[key] = e['value'].to_s
  elsif e.key?('valueFrom')
    skipped_value_from << key
  end
end

command = container['command']
args = container['args']

command = [] if command.nil?
args = [] if args.nil?

command = [command.to_s] unless command.is_a?(Array)
args = [args.to_s] unless args.is_a?(Array)

job_payload = {
  'name' => job['name'].to_s,
  'agent' => (job['agent'] || 'kubernetes').to_s,
  'cluster' => job['cluster'],
  'context' => job['context'],
  'decorate' => job['decorate'],
  'decoration_config' => job['decoration_config'],
  'spec' => job['spec'],
  'extra_refs' => job['extra_refs'],
  'rerun_command' => job['rerun_command']
}

result = {
  'kind' => selected['kind'],
  'repo' => selected['repo'],
  'job_name' => job['name'].to_s,
  'container_name' => container['name'].to_s,
  'image' => container['image'].to_s,
  'command' => command.map { |x| x.to_s },
  'args' => args.map { |x| x.to_s },
  'resources' => container['resources'],
  'env' => env_map,
  'skipped_value_from_envs' => skipped_value_from,
  'job' => job_payload
}

puts JSON.pretty_generate(result)
RUBY
}

if ! EXTRACT_JSON="$(extract_job)"; then
    fatal "failed to parse prow config: ${CONFIG}"
fi
[[ -n "$EXTRACT_JSON" ]] || fatal "failed to extract job from config"

IMAGE="$(jq -r '.image' <<<"$EXTRACT_JSON")"
CONTAINER_SELECTED="$(jq -r '.container_name' <<<"$EXTRACT_JSON")"
KIND_SELECTED="$(jq -r '.kind' <<<"$EXTRACT_JSON")"
REPO_SELECTED="$(jq -r '.repo // empty' <<<"$EXTRACT_JSON")"
SKIPPED_ENVS="$(jq -r '.skipped_value_from_envs[]?' <<<"$EXTRACT_JSON" | paste -sd ',' - || true)"

[[ -n "$IMAGE" && "$IMAGE" != "null" ]] || fatal "resolved container image is empty"

log "resolved job: name=${JOB_NAME}, kind=${KIND_SELECTED}, repo=${REPO_SELECTED:-N/A}, container=${CONTAINER_SELECTED}, image=${IMAGE}"
if [[ -n "$SKIPPED_ENVS" ]]; then
    log "warning: skipped env.valueFrom entries: ${SKIPPED_ENVS}"
fi

run_pod_mode() {
    local pod_deadline_ts
    pod_deadline_ts=$(( $(date +%s) + RUN_TIMEOUT ))

    run_kubectl_with_deadline() {
        local step_desc="$1"
        shift

        local now remaining timeout_marker cmd_pid watchdog_pid rc
        now="$(date +%s)"
        remaining=$(( pod_deadline_ts - now ))
        if (( remaining <= 0 )); then
            log "pod replay timeout after ${RUN_TIMEOUT}s before step: ${step_desc}"
            if [[ -n "${POD_NAME_ACTIVE:-}" ]]; then
                run_kubectl --namespace "$NAMESPACE" delete pod "$POD_NAME_ACTIVE" --grace-period=0 --force --ignore-not-found=true --wait=false >/dev/null 2>&1 || true
            fi
            return 124
        fi

        timeout_marker="$(mktemp)"
        rm -f "${timeout_marker}"

        set +e
        run_kubectl "$@" &
        cmd_pid="$!"
        (
            sleep "${remaining}"
            if kill -0 "${cmd_pid}" >/dev/null 2>&1; then
                printf 'timeout\n' > "${timeout_marker}"
                log "pod replay timeout after ${RUN_TIMEOUT}s during step: ${step_desc}"
                if [[ -n "${POD_NAME_ACTIVE:-}" ]]; then
                    run_kubectl --namespace "$NAMESPACE" delete pod "$POD_NAME_ACTIVE" --grace-period=0 --force --ignore-not-found=true --wait=false >/dev/null 2>&1 || true
                fi
                kill "${cmd_pid}" >/dev/null 2>&1 || true
            fi
        ) &
        watchdog_pid="$!"

        wait "${cmd_pid}"
        rc="$?"
        kill "${watchdog_pid}" >/dev/null 2>&1 || true
        wait "${watchdog_pid}" 2>/dev/null || true
        set -e

        if [[ -f "${timeout_marker}" ]]; then
            rm -f "${timeout_marker}"
            return 124
        fi
        rm -f "${timeout_marker}"
        return "${rc}"
    }

    decode_b64_token() {
        local token="$1"
        local decoded=""
        if decoded="$(printf '%s' "$token" | base64 --decode 2>/dev/null)"; then
            printf '%s' "$decoded"
            return 0
        fi
        if decoded="$(printf '%s' "$token" | base64 -d 2>/dev/null)"; then
            printf '%s' "$decoded"
            return 0
        fi
        if decoded="$(printf '%s' "$token" | base64 -D 2>/dev/null)"; then
            printf '%s' "$decoded"
            return 0
        fi
        return 1
    }

    local cmd_array=()
    local token_b64=""
    local token_decoded=""
    while IFS= read -r token_b64; do
        [[ -n "$token_b64" ]] || continue
        token_decoded="$(decode_b64_token "$token_b64")" || fatal "failed to decode command token via base64"
        cmd_array+=("$token_decoded")
    done < <(jq -r '.command[]? | @base64' <<<"$EXTRACT_JSON")

    local arg_array=()
    while IFS= read -r token_b64; do
        [[ -n "$token_b64" ]] || continue
        token_decoded="$(decode_b64_token "$token_b64")" || fatal "failed to decode args token via base64"
        arg_array+=("$token_decoded")
    done < <(jq -r '.args[]? | @base64' <<<"$EXTRACT_JSON")

    if [[ "${#cmd_array[@]}" -eq 0 ]]; then
        cmd_array=(/bin/bash -lc)
    fi

    local pod_name="$POD_NAME"
    if [[ -z "$pod_name" ]]; then
        local suffix
        suffix="$(date +%s)-$RANDOM"
        pod_name="prow-local-${JOB_NAME}-${suffix}"
        pod_name="$(tr '[:upper:]' '[:lower:]' <<<"$pod_name" | tr -cs 'a-z0-9-' '-' | sed 's/^-*//;s/-*$//' | cut -c1-63)"
    fi
    POD_NAME_ACTIVE="$pod_name"

    local repo_owner=""
    local repo_name=""
    if [[ -n "$REPO_SELECTED" && "$REPO_SELECTED" == */* ]]; then
        repo_owner="${REPO_SELECTED%%/*}"
        repo_name="${REPO_SELECTED##*/}"
    fi

    local env_file
    env_file="$(mktemp)"
    POD_ENV_FILE_ACTIVE="$env_file"

    local effective_git_url="$GIT_URL"
    local effective_git_ref="$GIT_REF"
    local default_pull_sha=""
    if [[ -z "$effective_git_url" && -n "$REPO_SELECTED" ]]; then
        effective_git_url="https://github.com/${REPO_SELECTED}.git"
    fi
    if [[ "$CHECKOUT_MODE" == "workspace" ]]; then
        default_pull_sha="$(git -C "$WORKSPACE" rev-parse HEAD 2>/dev/null || echo '')"
    fi
    if [[ -z "$effective_git_ref" ]]; then
        local pull_sha_default
        pull_sha_default="$(get_override PULL_PULL_SHA '')"
        if [[ -n "$pull_sha_default" ]]; then
            effective_git_ref="$pull_sha_default"
        else
            effective_git_ref="$(get_override PULL_BASE_REF master)"
        fi
    fi

    if [[ "$CHECKOUT_MODE" == "github" && -z "$effective_git_url" ]]; then
        fatal "git url is empty in github checkout mode; set --git-url or --repo"
    fi
    local host_git_token=""
    host_git_token="${REPLAY_GIT_TOKEN:-${GITHUB_TOKEN:-${GH_TOKEN:-${GIT_AUTH_TOKEN:-}}}}"

    {
        printf 'export REPO_OWNER=%q\n' "${repo_owner}"
        printf 'export REPO_NAME=%q\n' "${repo_name}"
        printf 'export PULL_BASE_REF=%q\n' "$(get_override PULL_BASE_REF master)"
        printf 'export PULL_NUMBER=%q\n' "$(get_override PULL_NUMBER 99999)"
        printf 'export PULL_PULL_SHA=%q\n' "$(get_override PULL_PULL_SHA "${default_pull_sha}")"
        printf 'export JOB_TYPE=%q\n' "${KIND_SELECTED}"
        printf 'export JOB_NAME=%q\n' "${JOB_NAME}"
        printf 'export REPLAY_CHECKOUT_MODE=%q\n' "${CHECKOUT_MODE}"
        printf 'export REPLAY_GIT_URL=%q\n' "${effective_git_url}"
        printf 'export REPLAY_GIT_REF=%q\n' "${effective_git_ref}"
        printf 'export REPLAY_GIT_CLONE_DEPTH=%q\n' "${GIT_CLONE_DEPTH}"
        printf 'export REPLAY_GIT_SUBMODULES=%q\n' "${GIT_SUBMODULES}"
        printf 'export REPLAY_GIT_WORKDIR=%q\n' "${POD_WORKDIR}"
        if [[ -n "$host_git_token" ]]; then
            printf 'export REPLAY_GIT_TOKEN=%q\n' "${host_git_token}"
        fi

        jq -r '.env | to_entries[] | [.key, .value] | @tsv' <<<"$EXTRACT_JSON" | while IFS=$'\t' read -r k v; do
            [[ -n "$k" ]] || continue
            printf 'export %s=%q\n' "$k" "$v"
        done

        local pair=""
        local k=""
        local v=""
        for pair in "${EXTRA_ENVS[@]:-}"; do
            [[ -n "$pair" ]] || continue
            [[ "$pair" == *=* ]] || fatal "--set-env must be KEY=VALUE, got: ${pair}"
            k="${pair%%=*}"
            v="${pair#*=}"
            [[ -n "$k" ]] || fatal "--set-env key is empty"
            printf 'export %s=%q\n' "$k" "$v"
        done
    } > "$env_file"

    local run_file
    run_file="$(mktemp)"
    POD_RUN_FILE_ACTIVE="$run_file"
    {
        echo '#!/usr/bin/env bash'
        echo 'set -euo pipefail'
        echo 'source /tmp/prow_env.sh'
        cat <<'SCRIPT'
if [[ "${REPLAY_CHECKOUT_MODE:-github}" == "github" ]]; then
  : "${REPLAY_GIT_URL:?REPLAY_GIT_URL is required for github checkout mode}"
  : "${REPLAY_GIT_WORKDIR:=/workspace/src}"
  : "${REPLAY_GIT_CLONE_DEPTH:=1}"

  if ! command -v git >/dev/null 2>&1; then
    echo "git is required in container for github checkout mode" >&2
    exit 1
  fi

  clone_url="${REPLAY_GIT_URL}"
  if [[ -n "${REPLAY_GIT_TOKEN:-}" && "${REPLAY_GIT_URL}" == https://github.com/* ]]; then
    clone_url="https://x-access-token:${REPLAY_GIT_TOKEN}@${REPLAY_GIT_URL#https://}"
  fi

  rm -rf "${REPLAY_GIT_WORKDIR}"
  mkdir -p "$(dirname "${REPLAY_GIT_WORKDIR}")"
  if [[ "${REPLAY_GIT_CLONE_DEPTH}" == "0" ]]; then
    git clone "${clone_url}" "${REPLAY_GIT_WORKDIR}"
  else
    git clone --depth "${REPLAY_GIT_CLONE_DEPTH}" "${clone_url}" "${REPLAY_GIT_WORKDIR}"
  fi
  cd "${REPLAY_GIT_WORKDIR}"

  if [[ -n "${PULL_PULL_SHA:-}" ]]; then
    if [[ "${REPLAY_GIT_CLONE_DEPTH}" == "0" ]]; then
      git fetch origin "${PULL_PULL_SHA}"
    else
      git fetch --depth=1 origin "${PULL_PULL_SHA}"
    fi
    git checkout -f "${PULL_PULL_SHA}"
  elif [[ -n "${REPLAY_GIT_REF:-}" ]]; then
    if [[ "${REPLAY_GIT_CLONE_DEPTH}" == "0" ]]; then
      git fetch origin "${REPLAY_GIT_REF}" || true
    else
      git fetch --depth=1 origin "${REPLAY_GIT_REF}" || true
    fi
    if git rev-parse --verify -q FETCH_HEAD >/dev/null 2>&1; then
      git checkout -f FETCH_HEAD
    else
      git checkout -f "${REPLAY_GIT_REF}"
    fi
  fi

  if [[ "${REPLAY_GIT_CLONE_DEPTH}" == "0" ]]; then
    git fetch --tags --force origin || true
  else
    git fetch --tags --force --depth=1 origin || true
  fi

  if [[ "${REPLAY_GIT_SUBMODULES:-false}" == "true" ]]; then
    # Optional: skip specific submodules in replay by setting
    # REPLAY_GIT_SUBMODULE_SKIP_PATHS as comma-separated paths.
    if [[ -n "${REPLAY_GIT_SUBMODULE_SKIP_PATHS:-}" ]]; then
      IFS=',' read -r -a _skip_paths <<<"${REPLAY_GIT_SUBMODULE_SKIP_PATHS}"
      for _path in "${_skip_paths[@]}"; do
        [[ -n "${_path}" ]] || continue
        git config "submodule.${_path}.update" none
      done
    fi

    if [[ -n "${REPLAY_GIT_TOKEN:-}" && "${REPLAY_GIT_URL}" == https://github.com/* ]]; then
      git config --global url."https://x-access-token:${REPLAY_GIT_TOKEN}@github.com/".insteadOf "git@github.com:"
    fi

    git submodule update --init --recursive
  fi
else
  cd /workspace
fi
SCRIPT
        printf 'exec'
        local token=""
        for token in "${cmd_array[@]}"; do
            printf ' %q' "$token"
        done
        for token in "${arg_array[@]}"; do
            printf ' %q' "$token"
        done
        echo
    } > "$run_file"
    chmod +x "$run_file"

    local pod_file
    pod_file="$(mktemp)"
    POD_SPEC_FILE_ACTIVE="$pod_file"
    local resources_block=""
    resources_block="$(ruby -rjson -ryaml -e '
data = JSON.parse(STDIN.read)
res = data["resources"]
if res.is_a?(Hash) && !res.empty?
  y = YAML.dump(res)
  y = y.sub(/\A---\s*\n/, "")
  y.each_line { |line| print "        #{line}" }
end
' <<<"$EXTRACT_JSON")"
    cat > "$pod_file" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: ${pod_name}
  namespace: ${NAMESPACE}
spec:
  restartPolicy: Never
  containers:
    - name: runner
      image: ${IMAGE}
      imagePullPolicy: IfNotPresent
      command: ["/bin/bash", "-lc", "trap 'exit 0' TERM INT; while true; do wait || true; sleep 1; done"]
YAML
    if [[ -n "$resources_block" ]]; then
        cat >> "$pod_file" <<YAML
      resources:
${resources_block}
YAML
    fi
    if [[ -n "$GIT_TOKEN_SECRET" ]]; then
        local secret_name="${GIT_TOKEN_SECRET%%:*}"
        local secret_key="${GIT_TOKEN_SECRET#*:}"
        if [[ -z "$secret_name" || -z "$secret_key" || "$secret_name" == "$secret_key" ]]; then
            fatal "--git-token-secret must be in <name:key> format"
        fi
        cat >> "$pod_file" <<YAML
      env:
        - name: REPLAY_GIT_TOKEN
          valueFrom:
            secretKeyRef:
              name: ${secret_name}
              key: ${secret_key}
YAML
    fi

    if [[ "$DRY_RUN" == "true" ]]; then
        log "dry-run enabled, no pod will be created"
        echo "----- extracted job -----"
        echo "$EXTRACT_JSON"
        echo "----- pod yaml -----"
        cat "$pod_file"
        echo "----- run script -----"
        cat "$run_file"
        rm -f "$env_file" "$run_file" "$pod_file"
        return 0
    fi

    cleanup_pod_mode() {
        local code=$?
        local cleanup_pod_name="${POD_NAME_ACTIVE:-}"
        local cleanup_env_file="${POD_ENV_FILE_ACTIVE:-}"
        local cleanup_run_file="${POD_RUN_FILE_ACTIVE:-}"
        local cleanup_pod_file="${POD_SPEC_FILE_ACTIVE:-}"
        if [[ "$KEEP_POD" == "true" ]]; then
            log "keep pod enabled, skip deleting pod ${cleanup_pod_name:-unknown}"
        else
            if [[ -n "$cleanup_pod_name" ]]; then
                run_kubectl --namespace "$NAMESPACE" delete pod "$cleanup_pod_name" --ignore-not-found=true --wait=false >/dev/null 2>&1 || true
            fi
        fi
        rm -f "$cleanup_env_file" "$cleanup_run_file" "$cleanup_pod_file"
        exit $code
    }
    trap cleanup_pod_mode EXIT

    run_kubectl_with_deadline "create replay pod" --namespace "$NAMESPACE" apply --validate=false -f "$pod_file"
    local rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        return "${rc}"
    fi
    log "waiting pod ready: ${pod_name}"
    local now remaining ready_timeout
    now="$(date +%s)"
    remaining=$(( pod_deadline_ts - now ))
    if (( remaining <= 0 )); then
        log "pod replay timeout after ${RUN_TIMEOUT}s before waiting pod ready"
        return 124
    fi
    ready_timeout="${POD_READY_TIMEOUT}"
    if (( remaining < ready_timeout )); then
        ready_timeout="${remaining}"
    fi
    run_kubectl_with_deadline "wait pod ready" --namespace "$NAMESPACE" wait --for=condition=Ready "pod/${pod_name}" --timeout="${ready_timeout}s"
    rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        return "${rc}"
    fi

    if [[ "$CHECKOUT_MODE" == "workspace" ]]; then
        log "copy workspace to pod:/workspace"
        run_kubectl_with_deadline "prepare workspace dir in pod" --namespace "$NAMESPACE" exec "$pod_name" -- mkdir -p /workspace
        rc="$?"
        if [[ "${rc}" -ne 0 ]]; then
            return "${rc}"
        fi
        run_kubectl_with_deadline "copy workspace into pod" --namespace "$NAMESPACE" cp "${WORKSPACE}/." "${pod_name}:/workspace"
        rc="$?"
        if [[ "${rc}" -ne 0 ]]; then
            return "${rc}"
        fi
    else
        log "skip workspace sync, pod will checkout from github"
    fi

    log "copy env and run script"
    run_kubectl_with_deadline "copy replay env file" --namespace "$NAMESPACE" cp "$env_file" "${pod_name}:/tmp/prow_env.sh"
    rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        return "${rc}"
    fi
    run_kubectl_with_deadline "copy replay run script" --namespace "$NAMESPACE" cp "$run_file" "${pod_name}:/tmp/prow_run.sh"
    rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        return "${rc}"
    fi
    run_kubectl_with_deadline "chmod replay run script" --namespace "$NAMESPACE" exec "$pod_name" -- chmod +x /tmp/prow_run.sh
    rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        return "${rc}"
    fi

    log "run job command (full replay timeout=${RUN_TIMEOUT}s)"
    run_kubectl_with_deadline "run replay job command" --namespace "$NAMESPACE" exec -i "$pod_name" -- /tmp/prow_run.sh
    rc="$?"
    if [[ "${rc}" -ne 0 ]]; then
        if [[ "${rc}" -eq 124 ]]; then
            log "pod replay timed out after ${RUN_TIMEOUT}s"
            return 1
        fi
        log "job command failed with exit code ${rc}"
        return "${rc}"
    fi
    log "job finished successfully"
}

run_prowjob_mode() {
    local base_ref
    local pull_number
    local pull_sha
    base_ref="$(get_override PULL_BASE_REF master)"
    pull_number="$(get_override PULL_NUMBER '')"
    pull_sha="$(get_override PULL_PULL_SHA '')"

    local extract_file
    extract_file="$(mktemp)"
    printf '%s\n' "$EXTRACT_JSON" > "$extract_file"

    local pj_file
    pj_file="$(mktemp)"

    ruby - "$extract_file" "$RUN_NAMESPACE" "$REPO_SELECTED" "$base_ref" "$pull_number" "$pull_sha" <<'RUBY' > "$pj_file"
require 'json'
require 'yaml'

data = JSON.parse(File.read(ARGV[0]))
run_namespace = ARGV[1]
repo_selected = ARGV[2]
base_ref = ARGV[3]
pull_number = ARGV[4]
pull_sha = ARGV[5]

kind = data['kind']
job = data['job'] || {}
job_name = (job['name'] || data['job_name']).to_s
context = (job['context'] || job_name).to_s

raise 'missing job spec' unless job['spec'].is_a?(Hash)

spec = {
  'type' => kind,
  'job' => job_name,
  'agent' => (job['agent'] || 'kubernetes').to_s,
  'namespace' => run_namespace,
  'pod_spec' => job['spec'],
  'report' => false
}

spec['cluster'] = job['cluster'] if job['cluster']
spec['context'] = context unless context.empty?
spec['rerun_command'] = job['rerun_command'] if job['rerun_command']
spec['decoration_config'] = job['decoration_config'] if job['decoration_config']
spec['extra_refs'] = job['extra_refs'] if job['extra_refs']

if kind == 'presubmit' || kind == 'postsubmit'
  if repo_selected.nil? || repo_selected.empty? || !repo_selected.include?('/')
    raise 'repo is required for presubmit/postsubmit (org/repo)'
  end
  org, repo = repo_selected.split('/', 2)
  refs = {
    'org' => org,
    'repo' => repo,
    'base_ref' => (base_ref.nil? || base_ref.empty? ? 'master' : base_ref)
  }
  if !pull_number.to_s.empty? && !pull_sha.to_s.empty?
    refs['pulls'] = [{'number' => pull_number.to_i, 'sha' => pull_sha}]
  end
  spec['refs'] = refs
end

metadata = {
  'generateName' => "#{job_name}-local-",
  'labels' => {
    'created-by-prow' => 'true',
    'prow.k8s.io/job' => job_name,
    'prow.k8s.io/type' => kind,
    'prow.k8s.io/context' => context
  },
  'annotations' => {
    'prow.k8s.io/job' => job_name,
    'prow.k8s.io/context' => context
  }
}

if spec['refs'].is_a?(Hash)
  refs = spec['refs']
  metadata['labels']['prow.k8s.io/refs.org'] = refs['org'].to_s if refs['org']
  metadata['labels']['prow.k8s.io/refs.repo'] = refs['repo'].to_s if refs['repo']
  metadata['labels']['prow.k8s.io/refs.base_ref'] = refs['base_ref'].to_s if refs['base_ref']
  if refs['pulls'].is_a?(Array) && !refs['pulls'].empty?
    metadata['labels']['prow.k8s.io/refs.pull'] = refs['pulls'][0]['number'].to_s
  end
end

doc = {
  'apiVersion' => 'prow.k8s.io/v1',
  'kind' => 'ProwJob',
  'metadata' => metadata,
  'spec' => spec
}

puts YAML.dump(doc)
RUBY

    if [[ "$DRY_RUN" == "true" ]]; then
        log "dry-run enabled, no prowjob will be created"
        echo "----- extracted job -----"
        echo "$EXTRACT_JSON"
        echo "----- prowjob yaml -----"
        cat "$pj_file"
        rm -f "$extract_file" "$pj_file"
        return 0
    fi

    local pj_name
    pj_name="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" create --validate=false -f "$pj_file" -o jsonpath='{.metadata.name}')"
    [[ -n "$pj_name" ]] || fatal "failed to create ProwJob"

    log "created prowjob: ${pj_name} (namespace=${PROWJOB_NAMESPACE}, run-namespace=${RUN_NAMESPACE})"

    local start_ts
    start_ts="$(date +%s)"
    local last_state=""
    local last_pod=""

    while true; do
        local state
        local pod_name
        local url
        local build_id
        local desc

        state="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" get prowjob "$pj_name" -o jsonpath='{.status.state}' 2>/dev/null || true)"
        pod_name="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" get prowjob "$pj_name" -o jsonpath='{.status.pod_name}' 2>/dev/null || true)"
        url="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" get prowjob "$pj_name" -o jsonpath='{.status.url}' 2>/dev/null || true)"
        build_id="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" get prowjob "$pj_name" -o jsonpath='{.status.build_id}' 2>/dev/null || true)"
        desc="$(run_kubectl --namespace "$PROWJOB_NAMESPACE" get prowjob "$pj_name" -o jsonpath='{.status.description}' 2>/dev/null || true)"

        if [[ "$state" != "$last_state" || "$pod_name" != "$last_pod" ]]; then
            log "prowjob status: state=${state:-N/A} pod=${pod_name:-N/A} build_id=${build_id:-N/A}"
            if [[ -n "$desc" ]]; then
                log "prowjob description: ${desc}"
            fi
            last_state="$state"
            last_pod="$pod_name"
        fi

        if [[ -n "$state" ]]; then
            case "$state" in
                success|failure|aborted|error)
                    local final_url="$url"
                    if [[ -z "$final_url" && -n "$build_id" ]]; then
                        final_url="${DECK_BASE_URL}/view/gs/prow-tidb-logs/${JOB_NAME}/${build_id}"
                    fi
                    echo "PROWJOB_NAME=${pj_name}"
                    echo "PROWJOB_STATE=${state}"
                    echo "PROWJOB_POD=${pod_name}"
                    if [[ -n "$final_url" ]]; then
                        echo "PROWJOB_URL=${final_url}"
                    fi

                    if [[ "$DELETE_PROWJOB" == "true" ]]; then
                        run_kubectl --namespace "$PROWJOB_NAMESPACE" delete prowjob "$pj_name" --ignore-not-found=true >/dev/null 2>&1 || true
                        log "deleted prowjob: ${pj_name}"
                    fi

                    rm -f "$extract_file" "$pj_file"

                    if [[ "$state" != "success" ]]; then
                        return 1
                    fi
                    return 0
                    ;;
            esac
        fi

        local now
        now="$(date +%s)"
        if (( now - start_ts > RUN_TIMEOUT )); then
            log "prowjob timeout after ${RUN_TIMEOUT}s"
            echo "PROWJOB_NAME=${pj_name}"
            echo "PROWJOB_STATE=timeout"
            echo "PROWJOB_POD=${pod_name:-}"
            if [[ -n "$url" ]]; then
                echo "PROWJOB_URL=${url}"
            fi
            rm -f "$extract_file" "$pj_file"
            return 1
        fi

        sleep "$POLL_INTERVAL"
    done
}

run_auto_mode() {
    local chosen_mode="pod"

    case "${KIND_SELECTED}" in
        periodic)
            chosen_mode="prowjob"
            log "auto mode: periodic job, use prowjob replay"
            ;;
        presubmit|postsubmit)
            if has_pull_context_override; then
                chosen_mode="prowjob"
                log "auto mode: explicit pull context found, use prowjob replay"
            else
                local hist_line hist_base hist_number hist_sha hist_pj
                if hist_line="$(resolve_latest_pull_context_from_history)"; then
                    IFS=$'\t' read -r hist_base hist_number hist_sha hist_pj <<<"${hist_line}"
                    append_override_if_missing PULL_BASE_REF "${hist_base}" || true
                    append_override_if_missing PULL_NUMBER "${hist_number}" || true
                    append_override_if_missing PULL_PULL_SHA "${hist_sha}" || true
                    chosen_mode="prowjob"
                    log "auto mode: use successful history context prowjob=${hist_pj:-unknown} base=${hist_base} pull=${hist_number}"
                else
                    chosen_mode="pod"
                    log "auto mode: no successful history context found, fallback to pod replay"
                fi
            fi
            ;;
        *)
            chosen_mode="pod"
            log "auto mode: unknown kind=${KIND_SELECTED}, fallback to pod replay"
            ;;
    esac

    if [[ "${chosen_mode}" == "prowjob" ]]; then
        run_prowjob_mode
    else
        run_pod_mode
    fi
}

if [[ "$MODE" == "pod" ]]; then
    run_pod_mode
elif [[ "$MODE" == "prowjob" ]]; then
    run_prowjob_mode
else
    run_auto_mode
fi
