#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cd "$tmpdir"

bash "$repo_root/scripts/plugins/analyze-go-test-from-bazel-output.sh" \
    "$repo_root/scripts/plugins/testdata/analyze-go-test-from-bazel-output/sample.log" >/dev/null

test -f bazel-target-output-L1-4.log
test -f bazel-target-output-L5-10.fail.race.log
test -f bazel-target-output-L11-14.timeout.log
test ! -e bazel-target-output-L1-4.fatal.log
