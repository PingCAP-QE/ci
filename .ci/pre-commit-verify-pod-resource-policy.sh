#!/usr/bin/env bash
# Pre-commit entry for the Pod resource policy.
#
# yq is required to inspect container resources. When it is not installed the
# check is skipped locally with a notice instead of failing the commit; the
# Prow presubmit `pull-verify-pod-resource-policy` installs yq and always
# enforces the policy, so a local skip cannot let a violation merge.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v yq >/dev/null 2>&1; then
  echo "yq not installed; skipping pod resource policy check (enforced by Prow)." >&2
  exit 0
fi

exec bash "${SCRIPT_DIR}/verify-pod-resource-policy.sh" "$@"
