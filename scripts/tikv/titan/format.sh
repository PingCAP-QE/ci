#!/usr/bin/env bash
set -euo pipefail

if ! command -v clang-format >/dev/null 2>&1; then
  yum install -y llvm-toolset-7.0
fi

if [[ -f /opt/rh/llvm-toolset-7.0/enable ]]; then
  set +u
  source /opt/rh/llvm-toolset-7.0/enable
  set -u
fi

find . \( -iname "*.h" -o -iname "*.cc" \) -print0 | \
  xargs -0 -L1 clang-format -style=google -i

if [[ -n "$(git diff --stat)" ]]; then
  echo "ERROR: titan format check failed."
  echo "clang-format produced changes for the following files:"
  git diff --name-only
  echo
  echo "Diff summary:"
  git diff --stat
  echo
  echo "To fix locally, run scripts/format-diff.sh in the titan repository and commit the formatting changes."
  exit 1
fi
