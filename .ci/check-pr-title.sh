#!/usr/bin/env bash

set -euo pipefail

readonly -a DEFAULT_ALLOWED_TYPES=(
    "build"
    "chore"
    "ci"
    "docs"
    "feat"
    "fix"
    "jobs"
    "perf"
    "pipelines"
    "prow"
    "refactor"
    "revert"
    "security"
    "style"
    "test"
    "tekton"
    "tools"
)
readonly CONVENTIONAL_TITLE_REGEX='^([a-z]+)(\(([a-z0-9][a-z0-9._/-]*)\))?(!)?: ([^[:space:]].*)( \(#[0-9]+\))?$'
readonly REVERT_TITLE_REGEX='^Revert ".+"( \(#[0-9]+\))?$'
readonly CI_HINT_REGEX='^[A-Za-z0-9][A-Za-z0-9._-]*=[^[:space:]|]+$'

TITLE=""
VERBOSE=0

usage() {
    cat <<'EOF'
Validate a pull request title locally or in CI.

Usage:
  .ci/check-pr-title.sh --title "<title>"
  printf '%s\n' "<title>" | .ci/check-pr-title.sh

Rules:
  - Conventional Commits style: <type>(<scope>): <subject>
  - Scope is optional: <type>: <subject>
  - Breaking changes may use ! before :
  - GitHub revert titles are also allowed: Revert "..."
  - Optional cherry-pick suffix is allowed: (#123)
  - Optional CI hints are allowed after the title:
      <title> | component=value [| other-component=value]
EOF
}

fatal() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

trim() {
    local value="$1"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    printf '%s' "$value"
}

join_by() {
    local delimiter="$1"
    shift
    local first=1
    local item

    for item in "$@"; do
        if (( first )); then
            printf '%s' "$item"
            first=0
            continue
        fi
        printf '%s%s' "$delimiter" "$item"
    done
}

contains_value() {
    local needle="$1"
    shift
    local item

    for item in "$@"; do
        [[ "$item" == "$needle" ]] && return 0
    done
    return 1
}

parse_args() {
    while (( $# > 0 )); do
        case "$1" in
            --title)
                [[ $# -ge 2 ]] || fatal "missing value for $1"
                TITLE="$2"
                shift 2
                ;;
            --verbose)
                VERBOSE=1
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                usage >&2
                fatal "unknown argument: $1"
                ;;
        esac
    done

    if [[ -z "$TITLE" ]] && [[ ! -t 0 ]]; then
        IFS= read -r TITLE || true
    fi

    [[ -n "$TITLE" ]] || fatal "provide --title or pipe a title via stdin"
}

print_success() {
    local title="$1"
    local type="$2"
    local scope="$3"
    local breaking="$4"
    local subject="$5"
    shift 5
    local -a ci_hints=("$@")

    printf 'PR title is valid.\n'
    if (( VERBOSE )); then
        printf '  title: %s\n' "$title"
        printf '  type: %s\n' "$type"
        if [[ -n "$scope" ]]; then
            printf '  scope: %s\n' "$scope"
        fi
        printf '  breaking-change: %s\n' "$breaking"
        printf '  subject: %s\n' "$subject"
        if [[ -n "${ci_hints[*]-}" ]]; then
            printf '  ci-hints: %s\n' "$(join_by ', ' "${ci_hints[@]}")"
        fi
    fi
}

print_validation_help() {
    cat >&2 <<EOF
Expected format:
  <type>(<scope>): <subject>
  <type>: <subject>
  Revert "..."

Allowed types:
  $(join_by ', ' "${DEFAULT_ALLOWED_TYPES[@]}")

Optional suffixes:
  - breaking change marker: !
  - cherry-pick reference: (#123)
  - CI hints: | component=value

Examples:
  ci(prow): add PR title self-check
  docs: document local PR title validation
  fix(tidb): refresh mysql test expectation | tidb-test=pr/2114
  Revert "ci(prow): add PR title self-check"
EOF
}

validate_title() {
    local title="$1"
    local base_title=""
    local -a ci_hints=()
    local -a raw_hints=()
    local ci_hint
    local remainder=""
    local type=""
    local scope=""
    local breaking_change="no"
    local subject=""
    local normalized_title=""

    normalized_title="$(trim "$title")"
    [[ "$title" == "$normalized_title" ]] || fatal "PR title must not start or end with whitespace"

    base_title="$title"
    if [[ "$title" == *" | "* ]]; then
        base_title="${title%% | *}"
        remainder="${title#"$base_title | "}"
        IFS='|' read -r -a raw_hints <<<"$remainder"
        for ci_hint in "${raw_hints[@]}"; do
            ci_hint="$(trim "$ci_hint")"
            [[ -n "$ci_hint" ]] || fatal "empty CI hint after '|'"
            [[ "$ci_hint" =~ $CI_HINT_REGEX ]] || {
                printf 'ERROR: invalid CI hint: %s\n' "$ci_hint" >&2
                print_validation_help
                exit 1
            }
            ci_hints+=("$ci_hint")
        done
    fi

    if [[ "$base_title" =~ $REVERT_TITLE_REGEX ]]; then
        print_success "$title" "revert" "" "no" "$base_title" "${ci_hints[@]+"${ci_hints[@]}"}"
        return 0
    fi

    if [[ ! "$base_title" =~ $CONVENTIONAL_TITLE_REGEX ]]; then
        printf 'ERROR: invalid PR title: %s\n' "$title" >&2
        print_validation_help
        exit 1
    fi

    type="${BASH_REMATCH[1]}"
    scope="${BASH_REMATCH[3]:-}"
    if [[ -n "${BASH_REMATCH[4]:-}" ]]; then
        breaking_change="yes"
    fi
    subject="${BASH_REMATCH[5]}"

    if ! contains_value "$type" "${DEFAULT_ALLOWED_TYPES[@]}"; then
        printf 'ERROR: unsupported PR title type: %s\n' "$type" >&2
        print_validation_help
        exit 1
    fi

    print_success "$title" "$type" "$scope" "$breaking_change" "$subject" "${ci_hints[@]+"${ci_hints[@]}"}"
}

main() {
    parse_args "$@"
    validate_title "$TITLE"
}

main "$@"
