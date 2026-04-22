#!/usr/bin/env bash
set -euo pipefail

if ! command -v gitleaks >/dev/null 2>&1; then
  echo "gitleaks is required but not found" >&2
  exit 1
fi

# Run in source mode to scan workspace contents in PR, fail-fast on findings.
gitleaks detect \
  --source . \
  --no-git \
  --redact \
  --verbose
