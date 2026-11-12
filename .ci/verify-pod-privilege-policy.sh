#!/usr/bin/env bash
set -euo pipefail

# Verify that Pod specs do not silently widen the privileged surface.
#
# A privileged container gets every capability and the host devices; a single
# container escape then becomes a node compromise. In CI there is usually a
# legitimate reason (a docker-in-docker sidecar, a kind cluster), but that is a
# decision to write down with an expiry date, not something a new Pod spec
# should be able to add silently.
#
# Two document shapes are covered:
#   * Jenkins agent Pod templates: `jenkins/jobs/**/pod*.yaml`
#     (a Kubernetes Pod manifest, `spec` at the top level)
#   * Prow job definitions: `prow-jobs/**/*.yaml`
#     (`.presubmits.<repo>[].spec`, `.postsubmits.<repo>[].spec`, `.periodics[].spec`)
#
# Six checks:
#   1. `securityContext.privileged: true` on a container   -> allowlist
#   2. a `hostPath` volume                                -> allowlist
#   3. UID 0: `runAsUser: 0` on a container or the Pod    -> allowlist
#   4. a Pod spec that runs a privileged container must set
#      `automountServiceAccountToken: false`              -> allowlist
#   5. a container or volume that binds a Secret           -> allowlist
#   6. a `serviceAccountName` other than `default`         -> allowlist
#
# Checks 5 and 6 exist because the merge ACL is delegated per OWNERS subtree
# while the Secrets and ServiceAccounts a pod may bind are namespace-wide: a
# SIG approver could otherwise add a job that mounts the Jenkins admin token or
# the org GitHub token without the central approvers seeing it.
#
# Check 4 exists because the projected ServiceAccount token turns a container
# escape into an API-server credential. A privileged container almost never
# needs it (our agents run as the namespace `default` ServiceAccount); a Pod
# that really does authenticate to the API records that in the allowlist.
#
# Scope: with no arguments the files changed between PULL_BASE_SHA and
# PULL_PULL_SHA are checked, so the pre-existing backlog does not block
# unrelated PRs. With file arguments (pre-commit) those files are checked.
#
# Allowlist format (.ci/pod-privilege-policy-allowlist.txt), tab separated:
#   <check>\t<path-regex>\t<subject>\t<review-date>\t<reason>
# <check> is privileged | hostPath | runAsUser | automount, or * for all four.
# <subject> is the container name (a volume name for hostPath, `<pod>` for a
# Pod-level securityContext), or * for any subject. An entry whose review date
# has passed is treated as expired and no longer exempts.

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"

usage() {
  cat <<'USAGE'
Check changed Pod specs for unrecorded privileged access.

Usage:
  .ci/verify-pod-privilege-policy.sh [--allowlist FILE] [--today YYYY-MM-DD] [file ...]

With file arguments only those files are checked. Otherwise the Pod templates
and Prow job definitions changed in PULL_BASE_SHA..PULL_PULL_SHA are checked
(PULL_BASE_SHA defaults to HEAD~1).

The allowlist and the expiry clock are command-line options, not environment
variables: this gate is the only thing that stops a delegated OWNERS subtree
from granting itself a namespace-wide Secret or ServiceAccount, so the inputs it
trusts must be visible in the reviewed job definition rather than set by
whatever invokes the script.
USAGE
}

ALLOWLIST_FILE=".ci/pod-privilege-policy-allowlist.txt"
TODAY="$(date -u +%Y-%m-%d)"
file_args=()
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --allowlist)
      ALLOWLIST_FILE="${2:?--allowlist needs a file}"
      shift 2
      ;;
    --today)
      TODAY="${2:?--today needs a date}"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      file_args+=("$1")
      shift
      ;;
  esac
done
set -- "${file_args[@]+"${file_args[@]}"}"

if ! command -v yq >/dev/null 2>&1; then
  echo "ERROR: yq (mikefarah/yq v4) is required" >&2
  exit 1
fi

is_pod_template() {
  [[ "$1" == *'/pod'*.yaml || "$1" == *'/pod'*.yml ]] && [[ "$1" == jenkins/jobs/* ]]
}

is_prow_jobs_file() {
  [[ "$1" == prow-jobs/*.yaml || "$1" == prow-jobs/*.yml ]]
}

changed_files=()
if [[ "$#" -gt 0 ]]; then
  for f in "$@"; do
    [[ -n "${f}" ]] || continue
    if is_pod_template "${f}" || is_prow_jobs_file "${f}"; then
      changed_files+=("${f}")
    fi
  done
else
  [[ -n "${BASE_SHA}" ]] || BASE_SHA="HEAD~1"
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    if is_pod_template "${line}" || is_prow_jobs_file "${line}"; then
      changed_files+=("${line}")
    fi
  done < <(git diff --name-only --diff-filter=d "${BASE_SHA}..${HEAD_SHA}" -- 'jenkins/jobs/**' 'prow-jobs/**' || true)
fi

if [[ "${#changed_files[@]}" -eq 0 ]]; then
  echo "No changed Pod specs to check."
  exit 0
fi

# Print one line per finding: <unit>|<check>|<subject>
# <unit> names the Prow job, or is `<pod>` for a Pod template.
list_violations() {
  local file="$1"
  local document

  if is_pod_template "${file}"; then
    document='[{"unit": "<pod>", "spec": (.spec)}]'
  else
    document='
      [
        ((.presubmits // {}) | to_entries[] | .key as $repo | .value[] | {"unit": "presubmit \($repo)/\(.name)", "spec": (.spec), "decoration": (.decoration_config // {})}),
        ((.postsubmits // {}) | to_entries[] | .key as $repo | .value[] | {"unit": "postsubmit \($repo)/\(.name)", "spec": (.spec), "decoration": (.decoration_config // {})}),
        ((.periodics // []) | .[] | {"unit": "periodic \(.name)", "spec": (.spec), "decoration": (.decoration_config // {})})
      ]
    '
  fi

  yq -o=json -I=0 e "${document}" "${file}" 2>/dev/null |
    yq -p=json -r e '
      .[] as $job |
      $job.spec as $spec |
      ((($spec.initContainers // []) + ($spec.containers // []))) as $containers |
      [
        ($containers[] | select((.securityContext.privileged // false) == true) | [$job.unit, "privileged", (.name // "<unnamed>")]),
        (($spec.volumes // [])[] | select(has("hostPath")) | [$job.unit, "hostPath", (.name // "<unnamed>")]),
        ($containers[] | select((.securityContext.runAsUser // null) == 0) | [$job.unit, "runAsUser", (.name // "<unnamed>")]),
        (select(($spec.securityContext.runAsUser // null) == 0) | [$job.unit, "runAsUser", "<pod>"]),
        (select((($containers | map(select((.securityContext.privileged // false) == true)) | length) > 0)
                and ($spec.automountServiceAccountToken != false))
          | [$job.unit, "automount", "<pod>"]),
        ($containers[] | (.env // [])[]? | select(.valueFrom.secretKeyRef.name? != null) | [$job.unit, "secret", .valueFrom.secretKeyRef.name]),
        ($containers[] | (.envFrom // [])[]? | select(.secretRef.name? != null) | [$job.unit, "secret", .secretRef.name]),
        (($spec.volumes // [])[] | select(.secret.secretName? != null) | [$job.unit, "secret", .secret.secretName]),
        (($spec.volumes // [])[] | (.projected.sources // [])[]? | select(.secret.name? != null) | [$job.unit, "secret", .secret.name]),
        ($spec.serviceAccountName // "" | select(. != "" and . != "default") | [$job.unit, "serviceAccount", .]),
        # The Prow decoration injects its own credentials into the job pod: the
        # SSH key used for the clone, the OAuth token for the GitHub client and
        # the GCS writer key. They are namespace-wide Secrets like any other, so
        # they need the same review.
        ($job.decoration.ssh_key_secrets // [] | .[] | [$job.unit, "secret", .]),
        ($job.decoration.oauth_token_secret.name // "" | select(. != "") | [$job.unit, "secret", .]),
        ($job.decoration.gcs_credentials_secret // "" | select(. != "") | [$job.unit, "secret", .])
      ] | .[] | join("|")
    ' 2>/dev/null || true
}

# Return 0 when the (file, check, subject) triple is exempt, 2 when a matching
# entry has expired, and 1 otherwise. The exempt entry is echoed when it
# matches.
allowlist_status() {
  local file_path="$1"
  local check="$2"
  local subject="$3"

  [[ -f "${ALLOWLIST_FILE}" ]] || return 1

  local expired=0
  local entry_check path_regex allowed_subject review_date _reason
  while IFS=$'\t' read -r entry_check path_regex allowed_subject review_date _reason; do
    [[ -n "${entry_check:-}" ]] || continue
    [[ "${entry_check}" =~ ^[[:space:]]*# ]] && continue
    [[ -n "${path_regex:-}" && -n "${allowed_subject:-}" && -n "${review_date:-}" ]] || continue

    [[ "${entry_check}" == "*" || "${entry_check}" == "${check}" ]] || continue
    [[ "${file_path}" =~ ${path_regex} ]] || continue
    [[ "${allowed_subject}" == "*" || "${allowed_subject}" == "${subject}" ]] || continue

    if [[ "${review_date}" < "${TODAY}" ]]; then
      expired=1
      continue
    fi

    echo "[ALLOWLIST] ${file_path}: ${check} ${subject} exempt (review ${review_date})"
    return 0
  done <"${ALLOWLIST_FILE}"

  [[ "${expired}" -eq 1 ]] && return 2
  return 1
}

violation_message() {
  local check="$1"
  local subject="$2"
  case "${check}" in
    privileged)
      echo "container \"${subject}\": privileged: true without an allowlist entry"
      ;;
    hostPath)
      echo "volume \"${subject}\": hostPath without an allowlist entry"
      ;;
    runAsUser)
      echo "principal \"${subject}\": runAsUser: 0 (root) without an allowlist entry"
      ;;
    automount)
      echo "privileged container reaches the ServiceAccount token: set automountServiceAccountToken: false (or record why it is needed)"
      ;;
    secret)
      echo "secret \"${subject}\": pod binds this Secret without an allowlist entry"
      ;;
    serviceAccount)
      echo "serviceAccount \"${subject}\": pod binds this ServiceAccount without an allowlist entry"
      ;;
  esac
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

  while IFS='|' read -r unit check subject; do
    [[ -n "${check:-}" ]] || continue

    where="${file}"
    [[ "${unit}" != "<pod>" ]] && where="${file} (${unit})"

    rc=0
    allowlist_status "${file}" "${check}" "${subject}" || rc=$?
    if [[ "${rc}" -eq 0 ]]; then
      allowlisted=$((allowlisted + 1))
    elif [[ "${rc}" -eq 2 ]]; then
      echo "[BLOCK] ${where}: $(violation_message "${check}" "${subject}") — allowlist entry expired, renew or remove it" >&2
      failed=1
    else
      echo "[BLOCK] ${where}: $(violation_message "${check}" "${subject}")" >&2
      echo "        add: ${check}\\t<path-regex>\\t${subject}\\t<review-date>\\t<reason> to ${ALLOWLIST_FILE}" >&2
      failed=1
    fi
  done < <(list_violations "${file}")
done

if [[ "${failed}" -ne 0 ]]; then
  echo "Pod privilege policy violations found. See ${ALLOWLIST_FILE} to record an exemption." >&2
  exit 1
fi

echo "Pod privilege policy check passed (${allowlisted} allowlisted finding(s))."
