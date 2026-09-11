#!/usr/bin/env bash
set -euo pipefail

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks is required but not found" >&2
  exit 1
fi

BASE_SHA="${PULL_BASE_SHA:-}"
HEAD_SHA="${PULL_PULL_SHA:-${PULL_HEAD_SHA:-HEAD}}"

if [[ -z "$BASE_SHA" ]]; then
  echo "PULL_BASE_SHA is empty, fallback to HEAD~1..HEAD" >&2
  BASE_SHA="HEAD~1"
fi

echo "Running incremental gitleaks scan on range: ${BASE_SHA}..${HEAD_SHA}"

gitleaks git \
  --log-opts "${BASE_SHA}..${HEAD_SHA}" \
  --redact \
  --verbose
