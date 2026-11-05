#!/usr/bin/env bash
set -euo pipefail

# Verify that CI manifests only reference storage classes from the allowlist.
#
# Why: storage class names are cluster-specific (e.g. GKE ships
# `standard-rwo`/`premium-rwo`/`dynamic-rwo` by default, but not
# `hyperdisk-rwo`). CI configs must reference the unified `ci-rwo` name so
# they keep working across clusters. See `docs` and the allowlist file for
# the canonical list of allowed names.
#
# FAIL-CLOSED allowlist: the allowlist is a reviewed file in this repository,
# resolved from the repository root. It is deliberately NOT overridable through
# an environment variable, so code under check cannot point the gate at a
# permissive allowlist.

if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "ERROR: not inside a git work tree; the storage-class policy check needs the repository root." >&2
  exit 1
fi
REPO_ROOT="$(git rev-parse --show-toplevel)"
ALLOWLIST_FILE="${REPO_ROOT}/.ci/storage-class-policy-allowlist.txt"

# Directories that are scanned for storageClassName references. The legacy
# `jobs/` and `pipelines/` trees were retired by the one-folder-per-job
# migration, so the manifests actually live under `jenkins/`, `prow-jobs/` and
# `tekton/`. Every entry must exist: a gate that silently skips a missing
# directory scans nothing and always passes, which is worse than failing.
SCAN_PATHS=(
  "jenkins"
  "libraries"
  "prow-jobs"
  "tekton"
)

declare -a ALLOWED_NAMES=()

load_allowlist() {
  [[ -f "${ALLOWLIST_FILE}" ]] || {
    echo "ERROR: allowlist file not found: ${ALLOWLIST_FILE}" >&2
    exit 1
  }
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    ALLOWED_NAMES+=("${line}")
  done < "${ALLOWLIST_FILE}"
}

# A storageClassName value is allowed when:
#   1. it is an environment-variable indirection (e.g. ${STORAGE_CLASSNAME_RWO},
#      resolved at deploy time from the cluster), or
#   2. it appears in the allowlist.
is_allowed() {
  local value="$1"
  local allowed

  if [[ "${value}" == \$\{* ]]; then
    return 0
  fi

  for allowed in "${ALLOWED_NAMES[@]+"${ALLOWED_NAMES[@]}"}"; do
    [[ "${value}" == "${allowed}" ]] && return 0
  done

  return 1
}

for path in "${SCAN_PATHS[@]}"; do
  if [[ ! -d "${REPO_ROOT}/${path}" ]]; then
    echo "ERROR: storage-class scan path does not exist: ${path}" >&2
    echo "       update SCAN_PATHS in ${BASH_SOURCE[0]} when a tree moves." >&2
    exit 1
  fi
done

load_allowlist

failed=0
scanned=0
report_violation() {
  echo "[BLOCK] ${1}:${2}: storageClassName \"${3}\" is not in the storage-class allowlist" >&2
  failed=1
}

for path in "${SCAN_PATHS[@]}"; do
  while IFS= read -r file; do
    [[ -f "${file}" ]] || continue

    # Extract the storageClassName value from lines like:
    #   storageClassName: ci-rwo
    #   storageClassName: 'ci-rwo'
    #   storageClassName: "ci-rwo"
    #   storageClassName: ${STORAGE_CLASSNAME_RWO}
    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      scanned=$((scanned + 1))
      # `grep -n` over one file prints "<line>:<content>" (no file prefix).
      file_path="${file}"
      line_no="${line%%:*}"
      content="${line#*:}"

      value="$(printf '%s\n' "${content}" | sed -nE "s/.*storageClassName[[:space:]]*:[[:space:]]*['\\\"]?([^'\\\"[:space:],})]+).*/\\1/p")"
      [[ -n "${value}" ]] || continue

      if ! is_allowed "${value}"; then
        report_violation "${file_path}" "${line_no}" "${value}"
      fi
    done < <(grep -nE "storageClassName[[:space:]]*:" "${file}" || true)
  done < <(find "${REPO_ROOT}/${path}" -type f \( -name '*.groovy' -o -name '*.yaml' -o -name '*.yml' \) | LC_ALL=C sort)
done

if [[ "${failed}" -ne 0 ]]; then
  echo "Storage class policy violations found. Use only names from: ${ALLOWLIST_FILE}" >&2
  exit 1
fi

echo "Storage class policy check passed (${scanned} storageClassName reference(s) checked)."
