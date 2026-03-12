#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Replay changed native Prow jobs in current PR/worktree.

Examples:
  .ci/replay-changed-prow-jobs.sh \
    --base-sha "$PULL_BASE_SHA" \
    --head-sha "$PULL_PULL_SHA"

  .ci/replay-changed-prow-jobs.sh \
    --base-sha <base_sha> \
    --head-sha <head_sha> \
    --dry-run

Options:
  --base-sha <sha>             Base SHA used for git diff. Default: $PULL_BASE_SHA.
  --head-sha <sha>             Head SHA used for git diff. Default: $PULL_PULL_SHA or HEAD.
  --namespace <ns>             Replay pod namespace. Default: prow-test-pods.
  --run-timeout <seconds>      Per replay timeout. Default: 3600.
  --pod-ready-timeout <sec>    Per replay pod ready timeout. Default: 300.
  --git-clone-depth <n>        Replay clone depth. Default: 1.
  --max-replays <count>        Max replay jobs in one run. Default: 3.
  --max-jobs-per-file <count>  Fallback max jobs per changed file. Default: 3.
  --git-submodules             Enable --git-submodules in replay.
  --dry-run                    Print replay commands only.
  --verbose                    Print detailed debug logs.
  -h, --help                   Show help.

Environment:
  REPLAY_SUBMODULE_SKIP_PATHS  Optional comma-separated submodule paths passed as
                               REPLAY_GIT_SUBMODULE_SKIP_PATHS to replay runs.
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
}

vlog() {
  if [[ "${VERBOSE}" == "true" ]]; then
    log "$*"
  fi
}

fatal() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_bin() {
  local bin="$1"
  command -v "${bin}" >/dev/null 2>&1 || fatal "missing required command: ${bin}"
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

infer_base_ref() {
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

  name_pat = sprintf("^%*sname:[[:space:]]*", indent + 2, "")
  if (name == "" && line ~ name_pat) {
    value = line
    sub(name_pat, "", value)
    name = trim_right(value)
  }

  jenkins_pat = sprintf("^%*sagent:[[:space:]]*jenkins([[:space:]]*#.*)?$", indent + 2, "")
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

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-}"
NAMESPACE="prow-test-pods"
RUN_TIMEOUT="3600"
POD_READY_TIMEOUT="300"
GIT_CLONE_DEPTH="1"
MAX_REPLAYS="3"
MAX_JOBS_PER_FILE="3"
GIT_SUBMODULES="false"
DRY_RUN="false"
VERBOSE="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-sha)
      BASE_SHA="$2"
      shift 2
      ;;
    --head-sha)
      HEAD_SHA="$2"
      shift 2
      ;;
    --namespace)
      NAMESPACE="$2"
      shift 2
      ;;
    --run-timeout)
      RUN_TIMEOUT="$2"
      shift 2
      ;;
    --pod-ready-timeout)
      POD_READY_TIMEOUT="$2"
      shift 2
      ;;
    --git-clone-depth)
      GIT_CLONE_DEPTH="$2"
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
    --git-submodules)
      GIT_SUBMODULES="true"
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
      fatal "unknown argument: $1"
      ;;
  esac
done

require_bin git
require_bin bash
require_bin sort

if [[ -z "${HEAD_SHA}" ]]; then
  HEAD_SHA="$(git rev-parse HEAD)"
fi
if [[ -z "${BASE_SHA}" ]]; then
  if git rev-parse --verify origin/main >/dev/null 2>&1; then
    BASE_SHA="$(git merge-base origin/main "${HEAD_SHA}")"
  else
    fatal "base sha is empty, set --base-sha or PULL_BASE_SHA"
  fi
fi

git cat-file -e "${BASE_SHA}^{commit}" 2>/dev/null || fatal "base sha not found in local git: ${BASE_SHA}"
git cat-file -e "${HEAD_SHA}^{commit}" 2>/dev/null || fatal "head sha not found in local git: ${HEAD_SHA}"

log "detect changed prow-jobs yaml files: base=${BASE_SHA} head=${HEAD_SHA}"
changed_files_file="$(mktemp)"
targets_file="$(mktemp)"
collect_changed_files "${BASE_SHA}" "${HEAD_SHA}" > "${changed_files_file}"

if [[ ! -s "${changed_files_file}" ]]; then
  log "no changed prow-jobs yaml files, skip replay"
  rm -f "${changed_files_file}" "${targets_file}"
  exit 0
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
  base_ref="$(infer_base_ref "${repo}" "${file}")"

  selected_jobs_file="$(mktemp)"
  select_jobs_for_file "${file}" "${BASE_SHA}" "${HEAD_SHA}" "${MAX_JOBS_PER_FILE}" > "${selected_jobs_file}"
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
  exit 0
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

require_bin kubectl
require_bin ruby
require_bin jq

failures=0
while IFS='|' read -r file job_name job_type repo base_ref; do

  cmd=(bash .ci/replay-prow-job.sh
    --mode pod
    --checkout-mode github
    --config "${file}"
    --job-name "${job_name}"
    --job-type "${job_type}"
    --namespace "${NAMESPACE}"
    --run-timeout "${RUN_TIMEOUT}"
    --pod-ready-timeout "${POD_READY_TIMEOUT}"
    --git-clone-depth "${GIT_CLONE_DEPTH}"
    --set-env "PULL_BASE_REF=${base_ref}"
  )

  if [[ "${GIT_SUBMODULES}" == "true" ]]; then
    cmd+=(--git-submodules)
  fi
  if [[ -n "${repo}" ]]; then
    cmd+=(--repo "${repo}")
  fi
  if [[ -n "${REPLAY_SUBMODULE_SKIP_PATHS:-}" ]]; then
    cmd+=(--set-env "REPLAY_GIT_SUBMODULE_SKIP_PATHS=${REPLAY_SUBMODULE_SKIP_PATHS}")
  fi
  if [[ "${DRY_RUN}" == "true" ]]; then
    cmd+=(--dry-run)
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
