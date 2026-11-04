#!/usr/bin/env bash
set -euo pipefail

# Verify that Jenkins agent Pod templates do not run containers with
# `securityContext.privileged: true` unless the debt is recorded.
#
# A privileged container gets every capability and the host devices; a single
# container escape then becomes a node compromise. In CI there is usually a
# legitimate reason (a docker-in-docker sidecar), but that is a decision to
# write down with an expiry date, not something a new pod template should be
# able to add silently.
#
# Scope: `jenkins/jobs/**/pod*.yaml` (Jenkins Kubernetes agent Pod templates).
# When file arguments are given (pre-commit), they are checked directly.
# Otherwise the Pod templates changed between PULL_BASE_SHA and PULL_PULL_SHA
# are checked, so the pre-existing backlog does not block unrelated PRs.
#
# Allowlist format (.ci/pod-privilege-policy-allowlist.txt), tab separated:
#   <path-regex>\t<container-name>\t<review-date>\t<reason>
# `*` as container-name matches every container in the file. An entry whose
# review date has passed is treated as expired and no longer exempts.

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"
ALLOWLIST_FILE="${POD_PRIVILEGE_POLICY_ALLOWLIST_FILE:-.ci/pod-privilege-policy-allowlist.txt}"
TODAY="${POD_PRIVILEGE_POLICY_TODAY:-$(date -u +%Y-%m-%d)}"

usage() {
  cat <<'USAGE'
Check changed Pod templates for unrecorded privileged containers.

Usage:
  .ci/verify-pod-privilege-policy.sh [file ...]

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

# Return 0 when the (file, container) pair is exempt, 2 when a matching entry
# has expired, and 1 otherwise. The exempt entry is echoed when it matches.
allowlist_status() {
  local file_path="$1"
  local container="$2"

  [[ -f "${ALLOWLIST_FILE}" ]] || return 1

  local expired=0
  local path_regex allowed_container review_date _reason
  while IFS=$'\t' read -r path_regex allowed_container review_date _reason; do
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

echo "Checking pod privilege policy on ${#changed_files[@]} changed file(s)"

failed=0
allowlisted=0
for file in "${changed_files[@]}"; do
  [[ -f "${file}" ]] || continue

  if ! yq e '.' "${file}" >/dev/null 2>&1; then
    echo "[BLOCK] ${file}: invalid YAML" >&2
    failed=1
    continue
  fi

  while IFS= read -r container; do
    [[ -n "${container}" ]] || continue

    rc=0
    allowlist_status "${file}" "${container}" || rc=$?
    if [[ "${rc}" -eq 0 ]]; then
      allowlisted=$((allowlisted + 1))
    elif [[ "${rc}" -eq 2 ]]; then
      echo "[BLOCK] ${file}: container \"${container}\": privileged allowlist entry expired, renew or remove it" >&2
      failed=1
    else
      echo "[BLOCK] ${file}: container \"${container}\": privileged: true without an allowlist entry" >&2
      echo "        add: <path-regex>\\t${container}\\t<review-date>\\t<reason> to ${ALLOWLIST_FILE}" >&2
      failed=1
    fi
  done < <(yq e '
    ((.spec.initContainers // []) + (.spec.containers // []))[] |
    select((.securityContext.privileged // false) == true) |
    (.name | tostring)
  ' "${file}")
done

if [[ "${failed}" -ne 0 ]]; then
  echo "Pod privilege policy violations found. See ${ALLOWLIST_FILE} to record an exemption." >&2
  exit 1
fi

echo "Pod privilege policy check passed (${allowlisted} allowlisted privileged container(s))."
