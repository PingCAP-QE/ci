#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: find_tidb_eol_candidates.sh <minor> [ci-root] [infra-root]

Examples:
  find_tidb_eol_candidates.sh 8.5
  find_tidb_eol_candidates.sh 8.5 /path/to/ci /path/to/ti-community-infra/configs
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 || $# -gt 3 ]]; then
  usage
  exit 1
fi

minor="$1"
ci_root="${2:-$(pwd)}"
infra_root="${3:-$ci_root/../../ti-community-infra/configs}"
minor_regex="${minor//./\\.}"
minor_compact="${minor//./}"

print_section() {
  printf '\n== %s ==\n' "$1"
}

run_rg() {
  local pattern="$1"
  shift
  rg -n --no-heading "$pattern" "$@" || true
}

print_section "Parameters"
printf 'minor=%s\n' "$minor"
printf 'ci_root=%s\n' "$ci_root"
printf 'infra_root=%s\n' "$infra_root"

print_section "Dedicated product-component release files in ci"
find "$ci_root/prow-jobs" -type f \
  \( -name "release-${minor}-*.yaml" -o -name "release-${minor}.yaml" \) 2>/dev/null | sort
find "$ci_root/jobs" -type d -path "*/release-${minor}" -print 2>/dev/null | sort
find "$ci_root/pipelines" -type d -path "*/release-${minor}" -print 2>/dev/null | sort

print_section "Shared ci config references"
printf 'Candidate search only: review vX.Y.* hits manually before deleting them.\n'
run_rg "release-${minor_regex}($|\\\\.)|release-${minor_regex}-|feature[/_]release-${minor_regex}([.-]|$)|v${minor_regex}\\\\.[0-9]+|v${minor_compact}" \
  "$ci_root/prow-jobs" \
  "$ci_root/jobs" \
  "$ci_root/pipelines"

print_section "ti-community-infra/configs references"
run_rg "affects-${minor_regex}|may-affects-${minor_regex}|needs-cherry-pick-release-${minor_regex}|type/cherry-pick-for-release-${minor_regex}|release-${minor_regex}|release-${minor_regex}\\\\.|feature/release-${minor_regex}\\\\.[0-9]+|v${minor_regex}\\\\.[0-9]+" \
  "$infra_root/prow/config/external_plugins_config.yaml" \
  "$infra_root/prow/config/labels.yaml" \
  "$infra_root/prow/config/config.yaml" \
  "$infra_root/prow/config/labels.md"
