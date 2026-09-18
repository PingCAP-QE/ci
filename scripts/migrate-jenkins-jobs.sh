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
#   --apply               move/rename files, rewrite references and create
#                         back-compat symlinks from the old paths
#   --cleanup             remove the legacy pipelines/ tree and the back-compat
#                         symlinks; refuses unless the reference checker is clean
#                         (run with --strict)
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
  --only PATH  Only migrate jobs under this <org>/<repo>/<branch> prefix.
  --dry-run    Print the plan only (default).
  --apply      Perform the migration and create back-compat symlinks.
  --cleanup    Remove the legacy pipelines/ tree and symlinks (requires a clean
               reference check).
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

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="${script_dir}/../.ci/check-jenkins-job-references.sh"

legacy_jobs_dir="${root}/jobs"
pipelines_dir="${root}/pipelines"
new_jobs_dir="${root}/jenkins/jobs"

if [[ ! -d "${legacy_jobs_dir}" ]]; then
  echo "no legacy jobs/ directory under ${root}; nothing to migrate" >&2
  exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to create relative symlinks" >&2
  exit 2
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

rel_symlink() {
  local link="$1" target="$2"
  mkdir -p "$(dirname "${link}")"
  local rel
  rel="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[2], os.path.dirname(sys.argv[1])))' "${link}" "${target}")"
  ln -sfn "${rel}" "${link}"
}

# --- cleanup mode ---

if [[ "${mode}" == "cleanup" ]]; then
  if [[ ! -x "${checker}" && ! -f "${checker}" ]]; then
    echo "reference checker not found: ${checker}" >&2
    exit 2
  fi
  check_out="$(bash "${checker}" --root "${root}" --quiet 2>&1)" || {
    printf '%s\n' "${check_out}" >&2
    echo "REFUSING cleanup: Jenkins job references are not clean (see above)." >&2
    exit 1
  }
  orphans="$(printf '%s\n' "${check_out}" | grep -c 'orphan artifact' || true)"
  if [[ "${orphans}" -gt 0 ]]; then
    log "Note: ${orphans} orphaned artifact(s) will be removed with the pipelines/ tree."
  fi
  find "${legacy_jobs_dir}" -type l -delete
  if [[ -d "${pipelines_dir}" ]]; then
    rm -rf "${pipelines_dir}"
  fi
  find "${legacy_jobs_dir}" -mindepth 1 -type d -empty -delete 2>/dev/null || true
  log "Cleaned up: removed legacy pipelines/ tree and back-compat symlinks."
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

  if [[ -n "${only}" && "${dirrel}/${job}" != "${only}"* ]]; then
    return 0
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

  # Already migrated?
  if [[ -e "${root}/${target_rel}/dsl.groovy" && -L "${dsl}" ]]; then
    log "UP-TO-DATE ${dirrel}/${job}"
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
    [[ -e "${root}/${tok}" ]] || continue
    aux_refs+=("${tok}")
  done < <(grep -oE 'pipelines/[A-Za-z0-9._/-]+' "${root}/${sp_old}" 2>/dev/null | sort -u || true)

  local new_sp="${target_rel}/Jenkinsfile"

  log "${mode_upper} ${dirrel}/${job}:"
  log "  - ${rel} -> ${target_rel}/dsl.groovy"
  log "  - ${sp_old} -> ${target_rel}/Jenkinsfile"
  for ((i = 0; i < n; i++)); do
    log "  - ${pod_olds[i]} -> ${target_rel}/${pod_news[i]}"
  done
  log "  - rewrite scriptPath -> ${new_sp}"
  for ((i = 0; i < n; i++)); do
    log "  - rewrite ${pod_vars[i]}"
  done
  local m
  for m in "${aux_refs[@]+"${aux_refs[@]}"}"; do
    log "  - ${m} -> jenkins/jobs/${m#pipelines/}"
  done
  if [[ -n "${nested_job_dir}" ]]; then
    log "  - move auxiliary files from ${nested_job_dir}/ -> ${target_rel}/"
  fi

  if [[ "${mode}" != "apply" ]]; then
    moved=$((moved + 1))
    return 0
  fi

  mkdir -p "${root}/${target_rel}"
  mv "${dsl}" "${root}/${target_rel}/dsl.groovy"
  mv "${root}/${sp_old}" "${root}/${target_rel}/Jenkinsfile"
  for ((i = 0; i < n; i++)); do
    mv "${root}/${pod_olds[i]}" "${root}/${target_rel}/${pod_news[i]}"
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
      [[ -e "${root}/${target_rel}/${base_name}" ]] && continue
      mv "${base_f}" "${root}/${target_rel}/${base_name}"
      rel_symlink "${base_f}" "${root}/${target_rel}/${base_name}"
    done
  fi

  # Mirror-move shared referenced files (e.g. a common/ helper script).
  local aux aux_new
  for aux in "${aux_refs[@]+"${aux_refs[@]}"}"; do
    aux_new="jenkins/jobs/${aux#pipelines/}"
    if [[ -e "${root}/${aux}" && ! -e "${root}/${aux_new}" ]]; then
      mkdir -p "$(dirname "${root}/${aux_new}")"
      mv "${root}/${aux}" "${root}/${aux_new}"
      rel_symlink "${root}/${aux}" "${root}/${aux_new}"
    fi
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
  sed -E "${sed_args[@]+"${sed_args[@]}"}" "${root}/${target_rel}/Jenkinsfile" | sed 's#pipelines/#jenkins/jobs/#g' >"${tmp}"
  mv "${tmp}" "${root}/${target_rel}/Jenkinsfile"

  rel_symlink "${legacy_jobs_dir}/${dirrel}/${job}.groovy" "${root}/${target_rel}/dsl.groovy"
  rel_symlink "${root}/${sp_old}" "${root}/${target_rel}/Jenkinsfile"
  for ((i = 0; i < n; i++)); do
    rel_symlink "${root}/${pod_olds[i]}" "${root}/${target_rel}/${pod_news[i]}"
  done

  moved=$((moved + 1))
  return 0
}

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
  if [[ -n "${only}" && "${folder_dir}" != "${only}"* && "${only}" != "${folder_dir}"* ]]; then
    continue
  fi
  if [[ -e "${target}" && -L "${folder_file}" ]]; then
    log "UP-TO-DATE folder: ${rel}"
    continue
  fi
  log "${mode_upper} folder: ${rel} -> jenkins/jobs/${rel}"
  if [[ "${mode}" == "apply" ]]; then
    mkdir -p "$(dirname "${target}")"
    mv "${folder_file}" "${target}"
    rel_symlink "${folder_file}" "${target}"
  fi
done < <(find "${legacy_jobs_dir}" \( -type f -o -type l \) -name 'aa_folder.groovy' | LC_ALL=C sort)

if [[ "${mode}" == "apply" ]]; then
  log "Migration applied: ${moved} job(s) migrated, ${skipped} skipped."
else
  log "Dry-run: ${moved} job(s) planned, ${skipped} skipped. Re-run with --apply to execute."
fi
