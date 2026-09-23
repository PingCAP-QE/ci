#!/usr/bin/env bash
# shellcheck disable=SC2016  # literal ${...} is intentional in reference checks
#
# migrate-jenkins-jobs.sh
#
# Migrate the legacy Jenkins layout (jobs/<...>/<job>.groovy +
# pipelines/<...>) to the one-folder-per-job layout:
#
#   jenkins/jobs/<org>/<repo>/<branch>/<job>/
#   ├── dsl.groovy
#   ├── Jenkinsfile
#   └── pod.yaml | pod-<purpose>.yaml
#
# Modes:
#   --dry-run  (default)  print the plan and change nothing
#   --apply               move the job DSL, pipeline and pod templates into the
#                         job folder and rewrite references. A source that a
#                         not-yet-migrated job still references is copied instead
#                         of moved. No back-compat symlinks are created: the
#                         Jenkins seed job discovers jobs in both `jobs/**` and
#                         `jenkins/jobs/**`. Unshared legacy files are removed, so
#                         the migration leaves no duplicate behind.
#   --cleanup             remove any remaining legacy pipelines/ tree and prune
#                         the emptied jobs/ tree; refuses unless the reference
#                         checker is clean.
#
# Usage: scripts/migrate-jenkins-jobs.sh [--root DIR] [--dry-run|--apply|--cleanup]
#
set -euo pipefail

mode="dry-run"
only=""
root="."

usage() {
  cat <<'USAGE'
Migrate legacy Jenkins jobs to the one-folder-per-job layout.

Usage: scripts/migrate-jenkins-jobs.sh [options]

Options:
  --root DIR   Repository root to migrate (default: current directory).
  --only PATH  Only migrate jobs under <org>/<repo>[/<branch>[/<job>]].
  --dry-run    Print the plan only (default).
  --apply      Perform the migration (no back-compat symlinks are created).
  --cleanup    Remove the legacy pipelines/ tree and prune the emptied jobs/
               tree (requires a clean reference check).
  -h, --help   Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) root="${2:-}"; shift 2 ;;
    --only) only="${2:-}"; shift 2 ;;
    --dry-run) mode="dry-run"; shift ;;
    --apply) mode="apply"; shift ;;
    --cleanup) mode="cleanup"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "${root}" || ! -d "${root}" ]]; then
  echo "root directory not found: ${root}" >&2
  exit 2
fi
root="$(cd "${root}" && pwd)"

ref_index_file="$(mktemp)"
ref_seen_file="$(mktemp)"
trap 'rm -f "${ref_index_file}" "${ref_seen_file}"' EXIT

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="${script_dir}/../.ci/check-jenkins-job-references.sh"

legacy_jobs_dir="${root}/jobs"
pipelines_dir="${root}/pipelines"
new_jobs_dir="${root}/jenkins/jobs"

if [[ ! -d "${legacy_jobs_dir}" ]]; then
  echo "no legacy jobs/ directory under ${root}; nothing to migrate" >&2
  exit 0
fi

moved=0 skipped=0
mode_upper="$(printf '%s' "${mode}" | tr '[:lower:]' '[:upper:]')"

log() { printf '%s\n' "$*"; }

# --- expression resolution (mirrors .ci/check-jenkins-job-references.sh) ---

collect_finals() {
  local file="$1"
  grep -oE "final[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[[:space:]]*['\"][^'\"]*['\"]" "${file}" 2>/dev/null \
    | sed -E "s/^final[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*['\"]([^'\"]*)['\"].*$/\1=\2/" || true
}

lookup_final() {
  local name="$1"
  shift
  local kv
  for kv in "$@"; do
    if [[ "${kv%%=*}" == "${name}" ]]; then
      printf '%s' "${kv#*=}"
      return 0
    fi
  done
  return 1
}

expand_vars() {
  local s="$1"
  shift
  local kv name val
  for kv in "$@"; do
    name="${kv%%=*}"
    val="${kv#*=}"
    s="${s//\$\{${name}\}/${val}}"
  done
  printf '%s' "${s}"
}

resolve_expr() {
  local expr="$1"
  shift
  expr="$(printf '%s' "${expr}" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')"
  case "${expr}" in
    \"*\") expr="${expr#\"}"; expr="${expr%\"}" ;;
    \'*\') expr="${expr#\'}"; expr="${expr%\'}" ;;
    *)
      local found
      if found="$(lookup_final "${expr}" "$@")"; then
        expr="${found}"
      fi
      ;;
  esac
  expand_vars "${expr}" "$@"
}

derive_dsl_pairs() {
  local dsl="$1"
  local rel dir base job branch orgrepo
  rel="${dsl#"${legacy_jobs_dir}/"}"
  base="$(basename "${rel}")"
  job="${base%.groovy}"
  dir="$(dirname "${rel}")"
  branch="$(printf '%s' "${dir}" | cut -d/ -f3)"
  orgrepo="$(printf '%s' "${dir}" | cut -d/ -f1-2)"
  printf 'fullRepo=%s\n' "${orgrepo}"
  printf 'fullRepoName=%s\n' "${orgrepo}"
  printf 'folder=%s\n' "${dir}"
  printf 'branchAlias=%s\n' "${branch}"
  printf 'jobName=%s\n' "${job}"
  printf 'JOB_BASE_NAME=%s\n' "${job}"
}

derive_pipeline_pairs() {
  local pfile="$1"
  local rel sub base dir job branch orgrepo
  rel="${pfile#"${root}/"}"
  case "${rel}" in
    pipelines/*) sub="${rel#pipelines/}" ;;
    jenkins/jobs/*) sub="${rel#jenkins/jobs/}" ;;
    *) sub="${rel}" ;;
  esac
  base="$(basename "${sub}")"
  if [[ "${base}" == "pipeline.groovy" || "${base}" == "Jenkinsfile" ]]; then
    dir="$(dirname "${sub}")"
    job="$(basename "${dir}")"
    branch="$(basename "$(dirname "${dir}")")"
    orgrepo="$(printf '%s' "${dir}" | cut -d/ -f1-2)"
  else
    job="${base%.groovy}"
    dir="$(dirname "${sub}")"
    branch="$(basename "${dir}")"
    orgrepo="$(printf '%s' "${dir}" | cut -d/ -f1-2)"
  fi
  printf 'GIT_FULL_REPO_NAME=%s\n' "${orgrepo}"
  printf 'GIT_FULL_REPO=%s\n' "${orgrepo}"
  printf 'REFS.org=%s\n' "$(printf '%s' "${dir}" | cut -d/ -f1)"
  printf 'REFS.repo=%s\n' "$(printf '%s' "${dir}" | cut -d/ -f2)"
  printf 'BRANCH_ALIAS=%s\n' "${branch}"
  printf 'JOB_BASE_NAME=%s\n' "${job}"
}

normalize_pod_base() {
  local b="$1"
  b="${b%.yaml}"
  b="${b%.yml}"
  case "${b}" in
    pod-*) printf '%s.yaml' "${b}" ;;
    pod) printf 'pod.yaml' ;;
    *-pod) printf 'pod-%s.yaml' "${b%-pod}" ;;
    *) printf 'pod-%s.yaml' "${b}" ;;
  esac
}

# move_file <src> <dst>: move src to dst, materializing a pre-existing symlink
# (from an older, symlink-based tool run) instead of moving the link itself.
move_file() {
  local src="$1" dst="$2"
  mkdir -p "$(dirname "${dst}")"
  if [[ -L "${src}" ]]; then
    cp -L "${src}" "${dst}"
    rm -f "${src}"
  else
    mv "${src}" "${dst}"
  fi
}

# copy_artifact <src> <dst>: copy a pipeline/pod/auxiliary artifact into the job
# folder, leaving the legacy source in place. Used only when the source is shared
# by another legacy job that has not been migrated yet.
copy_artifact() {
  local src="$1" dst="$2"
  if [[ "${src}" == "${dst}" ]]; then
    return 0
  fi
  mkdir -p "$(dirname "${dst}")"
  if [[ -L "${src}" ]]; then
    cp -L "${src}" "${dst}"
  else
    cp "${src}" "${dst}"
  fi
}

# The reference index counts how many legacy jobs/pipelines reference a
# `pipelines/...` path. Pipeline sharing must be detected from the *resolved*
# scriptPath (a shared pipeline is often referenced through a `${...}` template,
# e.g. two jobs pointing at the same `.../latest/...` pipeline), while pod and
# helper sharing is a literal path inside a pipeline file. The index is built
# once and decremented as jobs migrate, so the last referencing job moves the
# source away and no duplicate is left behind.
build_ref_index() {
  local dsl pairs sp_raw sp_old line
  local acc="${ref_index_file}.acc"
  : >"${acc}"

  while IFS= read -r dsl; do
    [[ -n "${dsl}" ]] || continue
    # Sharing is scoped to the repository: a `<repo>` pipeline can be referenced
    # by any of that repo's branches, so the pre-pass scans the whole repo even
    # when `--only` selects a single branch (migration may be applied in chunks).
    if [[ -n "${only}" ]]; then
      local scope="${only#/}" rel dirrel
      scope="${scope%/}"
      scope="$(printf '%s' "${scope}" | cut -d/ -f1-2)"
      rel="${dsl#"${legacy_jobs_dir}/"}"
      dirrel="$(dirname "${rel}")"
      if [[ "${dirrel}" != "${scope}" && "${dirrel}" != "${scope}"/* ]]; then
        continue
      fi
    fi
    pairs=()
    while IFS= read -r line; do [[ -n "${line}" ]] && pairs+=("${line}"); done < <(collect_finals "${dsl}")
    while IFS= read -r line; do [[ -n "${line}" ]] && pairs+=("${line}"); done < <(derive_dsl_pairs "${dsl}")
    sp_raw="$(grep -oE 'scriptPath\([^)]*\)' "${dsl}" | head -n1 || true)"
    [[ -n "${sp_raw}" ]] || continue
    sp_raw="${sp_raw#scriptPath(}"
    sp_raw="${sp_raw%)}"
    sp_old="$(resolve_expr "${sp_raw}" "${pairs[@]+"${pairs[@]}"}")"
    case "${sp_old}" in
      pipelines/*) [[ "${sp_old}" == *'${'* ]] || printf '%s\n' "${sp_old}" >>"${acc}" ;;
    esac
  done < <(find "${legacy_jobs_dir}" \( -type f -o -type l \) -name '*.groovy' ! -name 'aa_folder.groovy' | LC_ALL=C sort)

  # Literal pod/helper paths referenced from the pipeline files.
  if [[ -d "${pipelines_dir}" ]]; then
    grep -rHoE 'pipelines/[A-Za-z0-9._/-]+' "${pipelines_dir}" 2>/dev/null | sed -E 's/^[^:]+://' >>"${acc}" || true
  fi

  sort "${acc}" | uniq -c | awk '{print $2"\t"$1}' | sort >"${ref_index_file}"
  rm -f "${acc}"
}

# ref_count <path>: remaining references to <path>, i.e. the index count minus
# the references already consumed. Consuming appends, so it is O(1) and does not
# rewrite the index.
ref_count() {
  local p="$1" total seen
  total="$(awk -F'\t' -v k="$p" '$1==k{print $2; found=1} END{if(!found) print 0}' "${ref_index_file}")"
  seen="$( { grep -cF -x -- "${p}" "${ref_seen_file}" 2>/dev/null || true; } )"
  printf '%s' "$(( total - ${seen:-0} ))"
}

# consume_ref <path>: mark one reference as handled.
consume_ref() {
  printf '%s\n' "$1" >>"${ref_seen_file}"
}

# relocate_artifact <src> <dst> <move|copy>: move the artifact into the job
# folder, or copy it when it is shared and must stay for another job.
relocate_artifact() {
  local src="$1" dst="$2" action="$3"
  if [[ "${src}" == "${dst}" ]]; then
    return 0
  fi
  if [[ "${action}" == "move" ]]; then
    move_file "${src}" "${dst}"
  else
    copy_artifact "${src}" "${dst}"
  fi
}

# --- cleanup mode ---

if [[ "${mode}" == "cleanup" ]]; then
  if [[ ! -f "${checker}" ]]; then
    echo "reference checker not found: ${checker}" >&2
    exit 2
  fi

  # Gate 1: reference integrity must be clean.
  check_out="$(bash "${checker}" --root "${root}" --quiet 2>&1)" || {
    printf '%s\n' "${check_out}" >&2
    echo "REFUSING cleanup: Jenkins job references are not clean (see above)." >&2
    exit 1
  }
  orphans="$(printf '%s\n' "${check_out}" | grep -c 'orphan artifact' || true)"
  if [[ "${orphans}" -gt 0 ]]; then
    log "Note: ${orphans} orphaned artifact(s) will be removed with the pipelines/ tree."
  fi

  # Gate 2: pipeline syntax validation. Needs a Jenkins instance, so it runs only
  # when JENKINS_URL is available.
  if [[ -n "${JENKINS_URL:-}" && -f "${root}/.ci/verify-jenkins-pipelines.sh" ]]; then
    log "Running pipeline syntax validation (.ci/verify-jenkins-pipelines.sh)..."
    (cd "${root}" && bash .ci/verify-jenkins-pipelines.sh) || {
      echo "REFUSING cleanup: pipeline syntax validation failed." >&2
      exit 1
    }
  else
    log "WARN: pipeline syntax validation skipped (set JENKINS_URL to enable); manual precondition."
  fi

  # Gate 3: pod manifest validation. Needs yq.
  if command -v yq >/dev/null 2>&1 && [[ -f "${root}/.ci/verify-k8s-pod-yaml.sh" ]]; then
    log "Running pod manifest validation (.ci/verify-k8s-pod-yaml.sh)..."
    (cd "${root}" && sh .ci/verify-k8s-pod-yaml.sh) || {
      echo "REFUSING cleanup: pod manifest validation failed." >&2
      exit 1
    }
  else
    log "WARN: pod manifest validation skipped (yq not found); manual precondition."
  fi

  # Gate 4: a staging replay of the migrated jobs must have succeeded. It cannot
  # be automated from here.
  log "WARN: staging replay is a manual precondition; confirm it passed before promoting."

  if [[ -d "${pipelines_dir}" ]]; then
    rm -rf "${pipelines_dir}"
  fi
  find "${legacy_jobs_dir}" -mindepth 1 -type d -empty -delete 2>/dev/null || true
  if [[ -d "${legacy_jobs_dir}" ]] && [[ -z "$(find "${legacy_jobs_dir}" -mindepth 1 -print -quit)" ]]; then
    rmdir "${legacy_jobs_dir}" 2>/dev/null || true
  fi
  log "Cleaned up: removed the legacy pipelines/ tree and pruned the emptied jobs/ tree."
  exit 0
fi

# --- migrate jobs ---

migrate_job() {
  local dsl="$1"
  local rel dirrel job target_rel pairs sp_raw sp_old
  rel="${dsl#"${legacy_jobs_dir}/"}"
  dirrel="$(dirname "${rel}")"
  job="$(basename "${rel}")"
  job="${job%.groovy}"
  target_rel="jenkins/jobs/${dirrel}/${job}"

  if [[ -n "${only}" ]]; then
    local only_norm="${only#/}"
    only_norm="${only_norm%/}"
    local job_id="${dirrel}/${job}"
    # Exact job path, or a path-boundary prefix such as <org>/<repo>.
    if [[ "${job_id}" != "${only_norm}" && "${job_id}" != "${only_norm}"/* ]]; then
      return 0
    fi
  fi

  # Already migrated? Never overwrite an existing job folder; a legacy path that
  # is a leftover symlink is reported as up to date rather than re-processed.
  if [[ -e "${root}/${target_rel}/dsl.groovy" ]]; then
    if [[ ! -e "${dsl}" || -L "${dsl}" ]]; then
      log "UP-TO-DATE ${dirrel}/${job}"
    else
      log "WARN ${dirrel}/${job}: already migrated but the legacy DSL still exists"
    fi
    return 0
  fi

  # Non-standard paths are migrated but reported, so they can be reviewed.
  if [[ "$(printf '%s' "${dirrel}" | awk -F/ '{print NF}')" -ne 3 ]]; then
    log "WARN ${dirrel}/${job}: non-standard path (expected <org>/<repo>/<branch>)"
  fi

  pairs=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] && pairs+=("${line}")
  done < <(collect_finals "${dsl}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && pairs+=("${line}")
  done < <(derive_dsl_pairs "${dsl}")

  sp_raw="$(grep -oE 'scriptPath\([^)]*\)' "${dsl}" | head -n1 || true)"
  if [[ -z "${sp_raw}" ]]; then
    log "SKIP ${dirrel}/${job}: no scriptPath(...)"
    skipped=$((skipped + 1))
    return 0
  fi
  sp_raw="${sp_raw#scriptPath(}"
  sp_raw="${sp_raw%)}"
  sp_old="$(resolve_expr "${sp_raw}" "${pairs[@]+"${pairs[@]}"}")"
  if [[ "${sp_old}" == *'${'* ]]; then
    log "SKIP ${dirrel}/${job}: scriptPath uses unresolved runtime variables"
    skipped=$((skipped + 1))
    return 0
  fi
  if [[ ! -f "${root}/${sp_old}" ]]; then
    log "SKIP ${dirrel}/${job}: dangling scriptPath -> ${sp_old}"
    skipped=$((skipped + 1))
    return 0
  fi

  # Collect pod references.
  local pod_vars=() pod_olds=() pod_news=() pod_exprs=()
  local ppairs=() decl var expr old_pod
  ppairs=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] && ppairs+=("${line}")
  done < <(collect_finals "${root}/${sp_old}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && ppairs+=("${line}")
  done < <(derive_pipeline_pairs "${root}/${sp_old}")

  while IFS= read -r decl; do
    [[ -n "${decl}" ]] || continue
    var="$(printf '%s' "${decl}" | sed -E 's/^([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=.*$/\1/')"
    expr="$(printf '%s' "${decl}" | sed -E 's/^[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[[:space:]]*//')"
    old_pod="$(resolve_expr "${expr}" "${ppairs[@]+"${ppairs[@]}"}")"
    if [[ "${old_pod}" == *'${'* ]]; then
      log "SKIP ${dirrel}/${job}: ${var} uses unresolved runtime variables"
      skipped=$((skipped + 1))
      return 0
    fi
    if [[ ! -f "${root}/${old_pod}" ]]; then
      log "SKIP ${dirrel}/${job}: dangling ${var} -> ${old_pod}"
      skipped=$((skipped + 1))
      return 0
    fi
    pod_vars+=("${var}")
    pod_olds+=("${old_pod}")
    pod_news+=("")
    pod_exprs+=("${expr}")
  done < <(grep -oE '[A-Za-z_]*POD[A-Za-z_]*TEMPLATE[A-Za-z_]*[[:space:]]*=[[:space:]]*("[^"]*"|'"'"'[^'"'"']*'"'"')' "${root}/${sp_old}" 2>/dev/null || true)

  local n=${#pod_vars[@]} i base
  for ((i = 0; i < n; i++)); do
    if [[ "${n}" -le 1 ]]; then
      pod_news[i]="pod.yaml"
    else
      base="$(basename "${pod_olds[i]}")"
      case "${base}" in
        pod-*.yaml|pod-*.yml) pod_news[i]="${base}" ;;
        *) pod_news[i]="$(normalize_pod_base "${base}")" ;;
      esac
    fi
  done

  local nested_job_dir="" aux_refs=() aux_old skip tok
  if [[ "$(basename "${sp_old}")" == "pipeline.groovy" ]]; then
    nested_job_dir="$(dirname "${sp_old}")"
  fi
  while IFS= read -r tok; do
    [[ -n "${tok}" ]] || continue
    [[ "${tok}" == "${sp_old}" ]] && continue
    skip=0
    for aux_old in "${pod_olds[@]+"${pod_olds[@]}"}"; do
      if [[ "${tok}" == "${aux_old}" ]]; then skip=1; break; fi
    done
    [[ "${skip}" -eq 1 ]] && continue
    [[ -f "${root}/${tok}" ]] || continue
    aux_refs+=("${tok}")
  done < <(grep -oE 'pipelines/[A-Za-z0-9._/-]+' "${root}/${sp_old}" 2>/dev/null | sort -u || true)

  local new_sp="${target_rel}/Jenkinsfile"

  # A source another legacy job still references must be copied, not moved, so
  # that job keeps resolving. An unshared source is moved, so the migration
  # leaves no duplicate behind. Pods of a shared pipeline are copied too.
  local sp_action="move" pod_actions=() pod_action
  if [[ "$(ref_count "${sp_old}")" -gt 1 ]]; then
    sp_action="copy"
  fi
  for ((i = 0; i < n; i++)); do
    pod_action="copy"
    if [[ "${sp_action}" == "move" && "$(ref_count "${pod_olds[i]}")" -le 1 ]]; then
      pod_action="move"
    fi
    pod_actions[i]="${pod_action}"
  done

  # This job's references are consumed whether the run applies or is a dry-run,
  # so a later shared reference sees the migrated job as already handled.
  consume_ref "${sp_old}"
  for ((i = 0; i < n; i++)); do
    consume_ref "${pod_olds[i]}"
  done

  log "${mode_upper} ${dirrel}/${job}:"
  log "  - move ${rel} -> ${target_rel}/dsl.groovy"
  log "  - ${sp_action} ${sp_old} -> ${target_rel}/Jenkinsfile"
  for ((i = 0; i < n; i++)); do
    log "  - ${pod_actions[i]} ${pod_olds[i]} -> ${target_rel}/${pod_news[i]}"
  done
  log "  - rewrite scriptPath -> ${new_sp}"
  for ((i = 0; i < n; i++)); do
    log "  - rewrite ${pod_vars[i]}"
  done
  local m
  for m in "${aux_refs[@]+"${aux_refs[@]}"}"; do
    log "  - copy ${m} -> jenkins/jobs/${m#pipelines/}"
  done
  if [[ -n "${nested_job_dir}" ]]; then
    log "  - ${sp_action} auxiliary files from ${nested_job_dir}/ -> ${target_rel}/"
  fi

  if [[ "${mode}" != "apply" ]]; then
    moved=$((moved + 1))
    return 0
  fi

  mkdir -p "${root}/${target_rel}"
  move_file "${dsl}" "${root}/${target_rel}/dsl.groovy"
  relocate_artifact "${root}/${sp_old}" "${root}/${target_rel}/Jenkinsfile" "${sp_action}"
  for ((i = 0; i < n; i++)); do
    relocate_artifact "${root}/${pod_olds[i]}" "${root}/${target_rel}/${pod_news[i]}" "${pod_actions[i]}"
  done

  local tmp base_f base_name
  tmp="$(mktemp)"
  sed -E "s|scriptPath\([^)]*\)|scriptPath(\"${new_sp}\")|" "${root}/${target_rel}/dsl.groovy" >"${tmp}"
  mv "${tmp}" "${root}/${target_rel}/dsl.groovy"

  # Move auxiliary files that live in the job directory (nested jobs).
  if [[ -n "${nested_job_dir}" && -d "${root}/${nested_job_dir}" ]]; then
    for base_f in "${root}/${nested_job_dir}"/*; do
      [[ -e "${base_f}" ]] || continue
      base_name="$(basename "${base_f}")"
      local skip_aux=0 pbase
      for pbase in "${pod_olds[@]+"${pod_olds[@]}"}"; do
        [[ "$(basename "${pbase}")" == "${base_name}" ]] && skip_aux=1
      done
      [[ "${base_name}" == "$(basename "${sp_old}")" ]] && skip_aux=1
      [[ "${skip_aux}" -eq 1 ]] && continue
      [[ -e "${root}/${target_rel}/${base_name}" ]] && continue
      relocate_artifact "${base_f}" "${root}/${target_rel}/${base_name}" "${sp_action}"
    done
  fi

  # Relay shared referenced files (e.g. a common/ helper script) into the new
  # layout. The first job materializes the shared path; a later job moves the
  # legacy copy away only once no other legacy pipeline still references it.
  local aux aux_new aux_action
  for aux in "${aux_refs[@]+"${aux_refs[@]}"}"; do
    [[ -e "${root}/${aux}" ]] || continue
    aux_new="jenkins/jobs/${aux#pipelines/}"
    aux_action="copy"
    if [[ "${sp_action}" == "move" && "$(ref_count "${aux}")" -le 1 ]]; then
      aux_action="move"
    fi
    consume_ref "${aux}"
    if [[ -e "${root}/${aux_new}" ]]; then
      if [[ "${aux_action}" == "move" ]]; then
        rm -f "${root}/${aux}"
      fi
      continue
    fi
    mkdir -p "$(dirname "${root}/${aux_new}")"
    relocate_artifact "${root}/${aux}" "${root}/${aux_new}" "${aux_action}"
  done

  local sed_args=() new_expr old_base
  if [[ "${n}" -gt 0 ]]; then
    for ((i = 0; i < n; i++)); do
      old_base="$(basename "${pod_olds[i]}")"
      if [[ "${pod_exprs[i]}" == *'${'* && "$(dirname "${pod_olds[i]}")" == "pipelines/${dirrel}/${job}" ]]; then
        new_expr="${pod_exprs[i]/pipelines\//jenkins\/jobs\/}"
        new_expr="${new_expr//${old_base}/${pod_news[i]}}"
      else
        new_expr="\"${target_rel}/${pod_news[i]}\""
      fi
      sed_args+=(-e "s|(^[[:space:]]*final[[:space:]]+${pod_vars[i]}[[:space:]]*=[[:space:]]*).*$|\1${new_expr}|")
    done
  fi
  tmp="$(mktemp)"
  if [[ "${#sed_args[@]}" -gt 0 ]]; then
    sed -E "${sed_args[@]}" "${root}/${target_rel}/Jenkinsfile" | sed 's#pipelines/#jenkins/jobs/#g' >"${tmp}"
  else
    # No pod constants to rewrite. Passing an empty "${sed_args[@]}" would make
    # sed treat the Jenkinsfile path as its script and fail.
    sed 's#pipelines/#jenkins/jobs/#g' "${root}/${target_rel}/Jenkinsfile" >"${tmp}"
  fi
  mv "${tmp}" "${root}/${target_rel}/Jenkinsfile"

  moved=$((moved + 1))
  return 0
}

build_ref_index

while IFS= read -r dsl; do
  [[ -n "${dsl}" ]] || continue
  migrate_job "${dsl}"
done < <(find "${legacy_jobs_dir}" \( -type f -o -type l \) -name '*.groovy' ! -name 'aa_folder.groovy' | LC_ALL=C sort)

# Folder definition files are not jobs; move them to the mirrored new path.
while IFS= read -r folder_file; do
  [[ -n "${folder_file}" ]] || continue
  rel="${folder_file#"${legacy_jobs_dir}/"}"
  target="${new_jobs_dir}/${rel}"
  folder_dir="$(dirname "${rel}")"
  if [[ -n "${only}" ]]; then
    only_prefix="${only#/}"
    only_prefix="${only_prefix%/}"
    if [[ "${folder_dir}" != "${only_prefix}" && "${folder_dir}" != "${only_prefix}"/* ]]; then
      continue
    fi
  fi
  if [[ -e "${target}" && -L "${folder_file}" ]]; then
    log "UP-TO-DATE folder: ${rel}"
    continue
  fi
  log "${mode_upper} folder: ${rel} -> jenkins/jobs/${rel}"
  if [[ "${mode}" == "apply" ]]; then
    move_file "${folder_file}" "${target}"
  fi
done < <(find "${legacy_jobs_dir}" \( -type f -o -type l \) -name 'aa_folder.groovy' | LC_ALL=C sort)

# OWNERS files follow the jobs they describe. Both the `jobs/` and `pipelines/`
# trees carry OWNERS; after co-location they belong under `jenkins/jobs/`. An
# OWNERS that already exists in the new tree wins (the legacy duplicate is
# removed with the retired tree).
for owners_root in "${legacy_jobs_dir}" "${pipelines_dir}"; do
  [[ -d "${owners_root}" ]] || continue
  while IFS= read -r owners_file; do
    [[ -n "${owners_file}" ]] || continue
    rel="${owners_file#"${owners_root}/"}"
    target="${new_jobs_dir}/${rel}"
    owners_dir="$(dirname "${rel}")"
    if [[ -n "${only}" ]]; then
      only_prefix="${only#/}"
      only_prefix="${only_prefix%/}"
      if [[ "${owners_dir}" != "${only_prefix}" && "${owners_dir}" != "${only_prefix}"/* ]]; then
        continue
      fi
    fi
    if [[ -e "${target}" ]]; then
      continue
    fi
    log "${mode_upper} owners: ${rel} -> jenkins/jobs/${rel}"
    if [[ "${mode}" == "apply" ]]; then
      move_file "${owners_file}" "${target}"
    fi
  done < <(find "${owners_root}" -type f -name 'OWNERS' | LC_ALL=C sort)
done

if [[ "${mode}" == "apply" ]]; then
  log "Migration applied: ${moved} job(s) migrated, ${skipped} skipped."
else
  log "Dry-run: ${moved} job(s) planned, ${skipped} skipped. Re-run with --apply to execute."
fi
