#!/usr/bin/env bash
set -euo pipefail

# Verify that Jenkins agent Pod templates declare Guaranteed-QoS memory
# resources: every container (and init container) must set memory
# `requests == limits`. Exceptions must be registered in the allowlist with a
# reason and a review date.
#
# Scope: `jenkins/jobs/**/pod*.yaml` (Jenkins Kubernetes agent Pod templates).
# When file arguments are given (pre-commit), they are checked directly.
# Otherwise the Pod templates changed between PULL_BASE_SHA and PULL_PULL_SHA
# are checked, so the pre-existing backlog does not block unrelated PRs.
#
# Allowlist format (.ci/pod-resource-policy-allowlist.txt), tab separated:
#   <path-regex>\t<container-name>\t<review-date>\t<reason>
# `*` as container-name matches every container in the file. An entry whose
# review date is in the past is treated as expired and no longer exempts.

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"
ALLOWLIST_FILE="${POD_RESOURCE_POLICY_ALLOWLIST_FILE:-.ci/pod-resource-policy-allowlist.txt}"
TODAY="${POD_RESOURCE_POLICY_TODAY:-$(date -u +%Y-%m-%d)}"

usage() {
  cat <<'USAGE'
Check changed Pod templates for Guaranteed-QoS memory resources.

Usage:
  .ci/verify-pod-resource-policy.sh [file ...]

With file arguments only those files are checked. Otherwise the Pod templates
changed in PULL_BASE_SHA..PULL_PULL_SHA are checked (PULL_BASE_SHA defaults to
HEAD~1).
USAGE
}

case "${1:-}" in
  -h | --help)
    usage
    exit 0
    ;;
esac

if ! command -v yq >/dev/null 2>&1; then
  echo "ERROR: yq (mikefarah/yq v4) is required" >&2
  exit 1
fi

changed_files=()
if [[ "$#" -gt 0 ]]; then
  for f in "$@"; do
    [[ -n "${f}" ]] || continue
    case "$(basename "${f}")" in
      pod*.yaml | pod*.yml) changed_files+=("${f}") ;;
    esac
  done
else
  [[ -n "${BASE_SHA}" ]] || BASE_SHA="HEAD~1"
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    changed_files+=("${line}")
  done < <(git diff --name-only --diff-filter=d "${BASE_SHA}..${HEAD_SHA}" -- 'jenkins/jobs/**' |
    grep -E '(^|/)pod[^/]*\.ya?ml$' || true)
fi

if [[ "${#changed_files[@]}" -eq 0 ]]; then
  echo "No changed Pod templates to check."
  exit 0
fi

# Convert a Kubernetes memory quantity to bytes; return the input unchanged
# when the unit is not recognized so callers can still compare strings.
mem_to_bytes() {
  local v="$1" num unit
  [[ -n "${v}" ]] || {
    printf ''
    return
  }
  case "${v}" in
    *Ki)
      num="${v%Ki}"
      unit=1024
      ;;
    *Mi)
      num="${v%Mi}"
      unit=1048576
      ;;
    *Gi)
      num="${v%Gi}"
      unit=1073741824
      ;;
    *Ti)
      num="${v%Ti}"
      unit=1099511627776
      ;;
    *[0-9]k | *[0-9]K)
      num="${v%[kK]}"
      unit=1000
      ;;
    *[0-9]M)
      num="${v%M}"
      unit=1000000
      ;;
    *[0-9]G)
      num="${v%G}"
      unit=1000000000
      ;;
    *)
      printf '%s' "${v}"
      return
      ;;
  esac
  if [[ "${num}" =~ ^[0-9]+$ ]]; then
    printf '%s' "$((num * unit))"
  else
    printf '%s' "${v}"
  fi
}

mem_equal() {
  [[ "$(mem_to_bytes "$1")" == "$(mem_to_bytes "$2")" ]]
}

# Return 0 when the (file, container) pair is exempt, 2 when a matching entry
# has expired, and 1 otherwise. The exempt entry is echoed when it matches.
allowlist_status() {
  local file_path="$1"
  local container="$2"

  [[ -f "${ALLOWLIST_FILE}" ]] || return 1

  local expired=0
  local path_regex allowed_container review_date reason
  while IFS=$'\t' read -r path_regex allowed_container review_date reason; do
    [[ -n "${path_regex}" ]] || continue
    [[ "${path_regex}" =~ ^[[:space:]]*# ]] && continue
    [[ -n "${allowed_container}" ]] || continue

    [[ "${file_path}" =~ ${path_regex} ]] || continue
    [[ "${allowed_container}" == "*" || "${allowed_container}" == "${container}" ]] || continue

    if [[ -n "${review_date}" && "${review_date}" < "${TODAY}" ]]; then
      expired=1
      continue
    fi

    echo "[ALLOWLIST] ${file_path}: container \"${container}\" exempt (review ${review_date:-n/a})"
    return 0
  done <"${ALLOWLIST_FILE}"

  [[ "${expired}" -eq 1 ]] && return 2
  return 1
}

echo "Checking pod resource policy on ${#changed_files[@]} changed file(s)"

failed=0
warned=0
for file in "${changed_files[@]}"; do
  [[ -f "${file}" ]] || continue

  if ! yq e '.' "${file}" >/dev/null 2>&1; then
    echo "[BLOCK] ${file}: invalid YAML" >&2
    failed=1
    continue
  fi

  while IFS="|" read -r container mem_req mem_lim cpu_req cpu_lim; do
    [[ -n "${container}" ]] || continue

    violation=""
    if [[ -z "${mem_req}" || -z "${mem_lim}" ]]; then
      violation="memory request and limit must both be set (request=\"${mem_req}\" limit=\"${mem_lim}\")"
    elif ! mem_equal "${mem_req}" "${mem_lim}"; then
      violation="memory requests (${mem_req}) != limits (${mem_lim})"
    fi

    if [[ -n "${violation}" ]]; then
      rc=0
      allowlist_status "${file}" "${container}" || rc=$?
      if [[ "${rc}" -eq 0 ]]; then
        :
      elif [[ "${rc}" -eq 2 ]]; then
        echo "[BLOCK] ${file}: container \"${container}\": allowlist entry expired, renew or remove it (${violation})" >&2
        failed=1
      else
        echo "[BLOCK] ${file}: container \"${container}\": ${violation}" >&2
        failed=1
      fi
    fi

    if [[ "${cpu_req}" != "${cpu_lim}" ]]; then
      echo "[WARN] ${file}: container \"${container}\": cpu requests (${cpu_req:-none}) != limits (${cpu_lim:-none}); Pod stays Burstable"
      warned=1
    fi
  done < <(yq e '
    ((.spec.initContainers // []) + (.spec.containers // []))[] |
    [
      (.name | tostring),
      (.resources.requests.memory // "" | tostring),
      (.resources.limits.memory // "" | tostring),
      (.resources.requests.cpu // "" | tostring),
      (.resources.limits.cpu // "" | tostring)
    ] | join("|")
  ' "${file}")
done

if [[ "${failed}" -ne 0 ]]; then
  echo "Pod resource policy violations found. See ${ALLOWLIST_FILE} to request an exemption." >&2
  exit 1
fi

if [[ "${warned}" -eq 1 ]]; then
  echo "Pod resource policy check passed (with CPU Burstable warnings)."
else
  echo "Pod resource policy check passed."
fi
