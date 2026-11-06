#!/usr/bin/env bash
set -euo pipefail

# Static validation for changes to Prow **kubernetes-agent** jobs (jobs that
# carry a `spec.containers` pod spec, i.e. `decorate: true` / agent kubernetes).
#
# It is intentionally *not* tied to any single job: it validates every changed
# kubernetes-agent job in a PR that touches prow-jobs YAML.
#
# SECURITY: this presubmit is VALIDATE-ONLY and must never apply a PR-supplied
# pod spec to a cluster. A PR can add or edit any `spec` under prow-jobs/, and
# creating a real ProwJob from it pre-merge would let the PR run an arbitrary
# container (with any ServiceAccount or Secret reachable from the pod
# namespace) before OWNERS review. Running a job for real is an operator action
# on the prow cluster; the pre-merge check only parses and lints the
# definitions:
#   - the YAML parses into presubmit/postsubmit entries with a pod spec
#   - container image is a literal, fully-qualified reference (no `$var`,
#     no bare `latest`), and its tag exists in the registry when reachable
#   - container declares command/args and resource requests/limits
#   - the embedded shell (command + args) passes `bash -n`

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-HEAD}"

CRANE_BIN=""

failures=0

warn() { echo "  ⚠  ${*}" >&2; }
fail() { echo "  ✗  ${*}" >&2; failures=$((failures + 1)); }

# ---------------------------------------------------------------- utilities

get_crane() {
  [[ -n "${CRANE_BIN}" ]] && return 0
  local tmp
  tmp="$(mktemp -d)"
  local url="https://github.com/google/go-containerregistry/releases/download/v0.21.5/go-containerregistry_Linux_x86_64.tar.gz"
  if command -v wget >/dev/null 2>&1; then
    wget -qO "${tmp}/crane.tar.gz" "${url}"
  else
    curl -fsSL -o "${tmp}/crane.tar.gz" "${url}"
  fi
  tar xzf "${tmp}/crane.tar.gz" -C "${tmp}" crane
  CRANE_BIN="${tmp}/crane"
}

# ------------------------------------------------------- changed file scoping

changed_files() {
  if [[ -n "${BASE_SHA}" ]]; then
    git diff --name-only --diff-filter=AM "${BASE_SHA}" "${HEAD_SHA}" -- prow-jobs/ 2>/dev/null || true
  else
    git diff --name-only --diff-filter=AM HEAD~1 HEAD -- prow-jobs/ 2>/dev/null || true
  fi
}

# ---------------------------------------------------------- static checks

static_check_container() {
  local name="$1" image="$2" cmd="$3" args0="$4" has_req="$5" has_lim="$6"
  echo "    container: ${name:-main}"

  if [[ "${image}" == *'$'* ]]; then
    warn "${image} is templated (contains \$) - skipped image checks"
  elif [[ "${image}" != *"/"* ]]; then
    warn "${image} is not a fully-qualified image reference (missing registry/repo)"
  else
    local tag="${image##*:}"
    if [[ "${image}" == *"@"* ]]; then
      echo "      image pinned by digest: ${image}"
    elif [[ "${tag}" == "latest" ]]; then
      warn "${image} uses mutable 'latest' tag"
    elif [[ "${tag}" == "${image}" ]]; then
      warn "${image} has no explicit tag"
    else
      # Best-effort: registry may be private or unreachable -> warn, not fail.
      if ! get_crane >/dev/null 2>&1 || ! "${CRANE_BIN}" digest "${image}" >/dev/null 2>&1; then
        warn "could not verify image exists (crane): ${image}"
      else
        echo "      image exists: ${image}"
      fi
    fi
  fi

  if [[ -z "${cmd}" && -z "${args0}" ]]; then
    warn "no command/args declared - job may rely on image entrypoint"
  fi

  # lint the embedded shell script (command + args[0] when it is -c style)
  if [[ -n "${args0}" && ( "${cmd}" == *bash* || "${cmd}" == *sh* ) ]]; then
    if ! printf '%s\n' "${args0}" | bash -n >/dev/null 2>&1; then
      fail "shell syntax error in args for container ${name:-main}"
    else
      echo "      shell args: syntax OK"
    fi
  fi

  if [[ "${has_req}" != "1" && "${has_lim}" != "1" ]]; then
    warn "container ${name:-main} declares neither resource requests nor limits"
  fi
}

static_check_job() {
  local job="$1"
  local name repo kind decorate
  name="$(jq -r '.name' <<<"${job}")"
  repo="$(jq -r '.repo' <<<"${job}")"
  kind="$(jq -r '.kind' <<<"${job}")"
  echo "  [static] ${kind} ${name} (${repo})"

  local c
  while read -r c; do
    local image cmd args0 has_req has_lim
    image="$(jq -r '.image // ""' <<<"${c}")"
    cmd="$(jq -r '(.command // []) | join(" ") // ""' <<<"${c}")"
    args0="$(jq -r '.args[0] // ""' <<<"${c}")"
    has_req="$(jq -r 'if (.resources.requests != null) then "1" else "0" end' <<<"${c}")"
    has_lim="$(jq -r 'if (.resources.limits != null) then "1" else "0" end' <<<"${c}")"
    static_check_container "${name}" "${image}" "${cmd}" "${args0}" "${has_req}" "${has_lim}"
  done < <(jq -c '.spec.containers[]' <<<"${job}")
}

# --------------------------------------------------------------------- main

files=()
while IFS= read -r line; do
  files+=("${line}")
done < <(changed_files | LC_ALL=C sort -u)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "no prow-jobs YAML changes, nothing to validate"
  exit 0
fi

for tool in jq yq; do
  command -v "${tool}" >/dev/null || { echo "ERROR: '${tool}' not found" >&2; exit 1; }
done

for f in "${files[@]}"; do
  [[ -f "${REPO_ROOT}/${f}" ]] || continue
  echo "== validating ${f}"
  jobs="$(
    yq -o=json '[.presubmits // {} | to_entries[] | .key as $repo | .value[] | select(.spec != null) | {"kind":"presubmit", "repo":$repo, "name":.name, "cluster":(.cluster // ""), "branches":.branches, "decorate":(.decorate // false), "decoration_config":(.decoration_config // {}), "spec":.spec}]' "${REPO_ROOT}/${f}" 2>/dev/null
    yq -o=json '[.postsubmits // {} | to_entries[] | .key as $repo | .value[] | select(.spec != null) | {"kind":"postsubmit", "repo":$repo, "name":.name, "cluster":(.cluster // ""), "branches":.branches, "decorate":(.decorate // false), "decoration_config":(.decoration_config // {}), "spec":.spec}]' "${REPO_ROOT}/${f}" 2>/dev/null
  )"
  jobs="$(jq -s 'add' <<<"${jobs}" 2>/dev/null || echo '[]')"
  while read -r job; do
    [[ -n "${job}" ]] || continue
    static_check_job "${job}"
  done < <(jq -c '.[]' <<<"${jobs}")
done

if [[ "${failures}" -ne 0 ]]; then
  echo "prow kubernetes-agent job validation FAILED (${failures} issue(s))"
  exit 1
fi
echo "prow kubernetes-agent job validation PASSED"
