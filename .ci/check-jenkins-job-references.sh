#!/usr/bin/env bash
#
# check-jenkins-job-references.sh
#
# Verify that every Jenkins job reference resolves inside the repository.
#
# For each Jenkins job definition it checks that:
#   * every `scriptPath(...)` resolves to an existing pipeline file;
#   * every `POD_TEMPLATE_FILE*` reference resolves to an existing pod template.
# A pipeline or pod template that no job references is reported as an orphan
# (warning by default, error with --strict).
#
# Both layouts are supported:
#   new:    jenkins/jobs/<org>/<repo>/<branch>/<job>/{dsl.groovy,Jenkinsfile,pod*.yaml}
#   legacy: jobs/<org>/<repo>/<branch>/<job>.groovy + pipelines/**/...
#
# Usage: .ci/check-jenkins-job-references.sh [--root DIR] [--strict] [--quiet]
#
set -euo pipefail

root="."
strict=0
quiet=0

usage() {
  cat <<'USAGE'
Verify Jenkins job references (scriptPath and POD_TEMPLATE_FILE) resolve.

Usage: .ci/check-jenkins-job-references.sh [options]

Options:
  --root DIR   Repository root to scan (default: current directory).
  --strict     Treat orphaned artifacts as errors.
  --quiet      Only print warnings/errors and the final result.
  -h, --help   Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) root="${2:-}"; shift 2 ;;
    --strict) strict=1; shift ;;
    --quiet) quiet=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "${root}" || ! -d "${root}" ]]; then
  echo "root directory not found: ${root}" >&2
  exit 2
fi
root="$(cd "${root}" && pwd)"

if [[ -d "${root}/jenkins/jobs" ]]; then
  layout="new"
  jobs_dir="${root}/jenkins/jobs"
elif [[ -d "${root}/jobs" ]]; then
  layout="legacy"
  jobs_dir="${root}/jobs"
else
  echo "no Jenkins jobs directory found under ${root} (looked for jenkins/jobs or jobs)" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
referenced="${work}/referenced"
: >"${referenced}"

errors=0
warnings=0
jobs_checked=0
refs_checked=0

log() { [[ "${quiet}" -eq 1 ]] || printf '%s\n' "$*"; }
error() { printf 'ERROR: %s\n' "$*" >&2; errors=$((errors + 1)); }
warn() { printf 'WARN: %s\n' "$*" >&2; warnings=$((warnings + 1)); }

# collect_finals <file>: print "NAME=VALUE" for single-line final string decls.
collect_finals() {
  local file="$1"
  grep -oE "final[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=[[:space:]]*['\"][^'\"]*['\"]" "${file}" 2>/dev/null \
    | sed -E "s/^final[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*['\"]([^'\"]*)['\"].*$/\1=\2/" || true
}

# lookup_final <name> <pairs...>: first matching value wins.
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

# expand_vars <string> <pairs...>: replace ${NAME} with the pair values.
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

# resolve_expr <expr> <pairs...>: strip quotes / resolve a bare variable, expand.
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

# derive_dsl_pairs <dsl-abs>: path-derived variables for a job DSL file.
derive_dsl_pairs() {
  local dsl="$1"
  local sub rel dir base job branch orgrepo
  rel="${dsl#"${jobs_dir}/"}"
  if [[ "${layout}" == "new" ]]; then
    dir="$(dirname "${rel}")"
    job="$(basename "${dir}")"
    branch="$(basename "$(dirname "${dir}")")"
    orgrepo="$(printf '%s' "${dir}" | cut -d/ -f1-2)"
  else
    base="$(basename "${rel}")"
    job="${base%.groovy}"
    dir="$(dirname "${rel}")"
    branch="$(printf '%s' "${dir}" | cut -d/ -f3)"
    orgrepo="$(printf '%s' "${dir}" | cut -d/ -f1-2)"
  fi
  printf 'fullRepo=%s\n' "${orgrepo}"
  printf 'fullRepoName=%s\n' "${orgrepo}"
  printf 'folder=%s\n' "${dir}"
  printf 'branchAlias=%s\n' "${branch}"
  printf 'jobName=%s\n' "${job}"
  printf 'JOB_BASE_NAME=%s\n' "${job}"
}

# derive_pipeline_pairs <pipeline-abs>: path-derived variables for a pipeline.
derive_pipeline_pairs() {
  local pfile="$1"
  local rel sub tree base dir job branch orgrepo
  rel="${pfile#"${root}/"}"
  case "${rel}" in
    pipelines/*) tree="pipelines/" ;;
    jenkins/jobs/*) tree="jenkins/jobs/" ;;
    *) tree="" ;;
  esac
  sub="${rel#"${tree}"}"
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

# check_ref <src-abs> <kind> <path>: record + verify a referenced path.
check_ref() {
  local src="$1" kind="$2" path="$3"
  local rel abs
  refs_checked=$((refs_checked + 1))
  # shellcheck disable=SC2016
  if [[ "${path}" == *'${'* ]]; then
    warn "${src#"${root}/"}: ${kind} uses an unresolved runtime variable: ${path}"
    return 0
  fi
  rel="${path#"${root}/"}"
  if [[ -z "${rel}" || "${rel}" == /* ]]; then
    error "${src#"${root}/"}: ${kind} must be repo-root-relative: '${path}'"
    return 1
  fi
  abs="${root}/${rel}"
  if [[ -e "${abs}" ]]; then
    printf '%s\n' "${abs}" >>"${referenced}"
    return 0
  fi
  error "${src#"${root}/"}: ${kind} target not found: ${rel}"
  return 1
}

while IFS= read -r dsl; do
  [[ -n "${dsl}" ]] || continue
  [[ "$(basename "${dsl}")" == "aa_folder.groovy" ]] && continue
  jobs_checked=$((jobs_checked + 1))

  pairs=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] && pairs+=("${line}")
  done < <(collect_finals "${dsl}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && pairs+=("${line}")
  done < <(derive_dsl_pairs "${dsl}")

  sp_raw="$(grep -oE 'scriptPath\([^)]*\)' "${dsl}" | head -n1 || true)"
  if [[ -z "${sp_raw}" ]]; then
    warn "${dsl#"${root}/"}: no scriptPath(...) found"
    continue
  fi
  sp_raw="${sp_raw#scriptPath(}"
  sp_raw="${sp_raw%)}"
  sp_value="$(resolve_expr "${sp_raw}" "${pairs[@]+"${pairs[@]}"}")"
  check_ref "${dsl}" "scriptPath" "${sp_value}" || true

  pipeline_abs="${root}/${sp_value#"${root}/"}"
  [[ -f "${pipeline_abs}" ]] || continue

  ppairs=()
  while IFS= read -r line; do
    [[ -n "${line}" ]] && ppairs+=("${line}")
  done < <(collect_finals "${pipeline_abs}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && ppairs+=("${line}")
  done < <(derive_pipeline_pairs "${pipeline_abs}")

  while IFS= read -r decl; do
    [[ -n "${decl}" ]] || continue
    expr="${decl#*=}"
    pval="$(resolve_expr "${expr}" "${ppairs[@]+"${ppairs[@]}"}")"
    check_ref "${pipeline_abs}" "POD_TEMPLATE" "${pval}" || true
  done < <(grep -oE '[A-Za-z_]*POD[A-Za-z_]*TEMPLATE[A-Za-z_]*[[:space:]]*=[[:space:]]*("[^"]*"|'"'"'[^'"'"']*'"'"')' "${pipeline_abs}" 2>/dev/null || true)
done < <(find "${jobs_dir}" -type f -name '*.groovy' | LC_ALL=C sort)

# Orphan detection.
if [[ "${layout}" == "new" ]]; then
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    grep -qxF "${f}" "${referenced}" || warn "orphan artifact: ${f#"${root}/"}"
  done < <(find "${jobs_dir}" -type f \( -name 'Jenkinsfile' -o -name 'pod*.yaml' -o -name 'pod*.yml' \) | LC_ALL=C sort)
elif [[ -d "${root}/pipelines" ]]; then
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    grep -qxF "${f}" "${referenced}" || warn "orphan artifact: ${f#"${root}/"}"
  done < <(find "${root}/pipelines" -type f \( -name '*.groovy' -o -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
fi

log "Checked ${jobs_checked} job(s) and ${refs_checked} reference(s) in ${layout} layout."

if [[ "${errors}" -gt 0 ]]; then
  echo "FAIL: ${errors} dangling reference(s) found." >&2
  exit 1
fi
if [[ "${warnings}" -gt 0 && "${strict}" -eq 1 ]]; then
  echo "FAIL: ${warnings} orphaned artifact(s) found (--strict)." >&2
  exit 1
fi
if [[ "${warnings}" -gt 0 ]]; then
  log "Note: ${warnings} orphaned artifact(s) found (not fatal without --strict)."
fi
echo "OK: all Jenkins job references resolve."
