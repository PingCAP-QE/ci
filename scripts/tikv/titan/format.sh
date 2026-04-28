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

if [[ -n "$(git diff --stat .)" ]]; then
  echo "❌ [ERROR] these files are not formatted: 👇👇👇"
  git diff --name-only .
  echo "👆👆👆 Please format these files and run it again!"
  echo
  echo "Diff summary:"
  git diff --stat .
  echo
  echo "To fix locally, run clang-format -style=google -i on the listed files, then commit the formatting changes."
  exit 1
fi
