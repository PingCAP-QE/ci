#!/usr/bin/env bash

set -euo pipefail

readonly PINGCAP_HOST_PATTERN='([[:alnum:]-]+\.)+pingcap\.net'
readonly -a ALLOWED_PINGCAP_HOSTS=(
    "do.pingcap.net"
    "cla.pingcap.net"
)
readonly -a FORBIDDEN_LITERAL_SUBSTRINGS=(
    "FILESERVER"
    "FILE_SERVER"
)

BASE_SHA=""
REPORT_FILE=""
HAS_VIOLATION=0

usage() {
    cat <<'USAGE'
Check added pull request lines against repository content policy.

Usage:
  .ci/check-pr-content-policy.sh --base-sha <sha>

The script compares <sha> against the current checkout (HEAD).
USAGE
}

fatal() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
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

cleanup() {
    rm -f "$REPORT_FILE"
}

append_unique_value() {
    local value="$1"
    local values_name="$2"
    local -n values_ref="$values_name"
    local existing

    for existing in "${values_ref[@]}"; do
        [[ "$existing" == "$value" ]] && return 0
    done
    values_ref+=("$value")
}

is_allowed_pingcap_host() {
    local host="$1"
    local allowed_host

    for allowed_host in "${ALLOWED_PINGCAP_HOSTS[@]}"; do
        [[ "$allowed_host" == "$host" ]] && return 0
    done
    return 1
}

parse_args() {
    BASE_SHA=""

    while (( $# > 0 )); do
        case "$1" in
            --base-sha)
                [[ $# -ge 2 ]] || fatal "missing value for $1"
                BASE_SHA="$2"
                shift 2
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
}

validate_shas() {
    [[ -n "$BASE_SHA" ]] || fatal "missing base SHA; pass --base-sha"

    git rev-parse --verify -q "${BASE_SHA}^{commit}" >/dev/null || fatal "base SHA not found: ${BASE_SHA}"
    git rev-parse --verify -q HEAD^{commit} >/dev/null || fatal "current HEAD commit not found"
}

init_report_file() {
    REPORT_FILE="$(mktemp)"
    trap cleanup EXIT
}

record_violation() {
    local file_path="$1"
    local line_no="$2"
    local content="$3"
    shift 3
    local -a reasons=("$@")

    {
        printf '%s:%s: %s\n' "$file_path" "$line_no" "$(join_by '; ' "${reasons[@]}")"
        printf '    %s\n' "$content"
    } >>"$REPORT_FILE"
}

append_forbidden_literal_substring_reasons() {
    local content="$1"
    local reasons_name="$2"
    local -n reasons_ref="$reasons_name"
    local literal

    for literal in "${FORBIDDEN_LITERAL_SUBSTRINGS[@]}"; do
        if [[ "$content" == *"$literal"* ]]; then
            append_unique_value "contains forbidden substring ${literal}" "$reasons_name"
        fi
    done
}

append_forbidden_pingcap_host_reasons() {
    local content="$1"
    local reasons_name="$2"
    local -n reasons_ref="$reasons_name"
    local host
    local lower_host

    while IFS= read -r host; do
        [[ -n "$host" ]] || continue
        lower_host="${host,,}"
        if is_allowed_pingcap_host "$lower_host"; then
            continue
        fi
        append_unique_value "contains forbidden host ${lower_host}" "$reasons_name"
    done < <(printf '%s\n' "$content" | grep -ioE "$PINGCAP_HOST_PATTERN" || true)
}

collect_policy_reasons() {
    local content="$1"
    local reasons_name="$2"
    local -n reasons_ref="$reasons_name"

    append_forbidden_literal_substring_reasons "$content" "$reasons_name"
    append_forbidden_pingcap_host_reasons "$content" "$reasons_name"
}

check_added_line() {
    local file_path="$1"
    local line_no="$2"
    local content="$3"
    local -a reasons=()

    collect_policy_reasons "$content" reasons
    (( ${#reasons[@]} > 0 )) || return 0

    record_violation "$file_path" "$line_no" "$content" "${reasons[@]}"
    return 1
}

scan_added_diff_lines() {
    local current_file=""
    local current_new_line=0
    local diff_line

    while IFS= read -r diff_line; do
        case "$diff_line" in
            "+++ b/"*)
                current_file="${diff_line#+++ b/}"
                ;;
            @@\ -*)
                if [[ "$diff_line" =~ ^@@\ -[0-9]+(,[0-9]+)?\ \+([0-9]+)(,[0-9]+)?\ @@ ]]; then
                    current_new_line="${BASH_REMATCH[2]}"
                fi
                ;;
            "+"*)
                if [[ "$diff_line" == "+++"* || -z "$current_file" ]]; then
                    continue
                fi

                if ! check_added_line "$current_file" "$current_new_line" "${diff_line#+}"; then
                    HAS_VIOLATION=1
                fi
                (( current_new_line++ ))
                ;;
            " "*)
                (( current_new_line++ ))
                ;;
        esac
    done < <(git diff --no-color --unified=0 --text "$BASE_SHA" HEAD)
}

print_policy_report() {
    if (( HAS_VIOLATION == 0 )); then
        echo "No PR content policy violations detected in added pull request lines."
        return 0
    fi

    cat <<'EOF' >&2
PR content policy violations detected in added pull request lines.
Current rules:
  - deny literal substrings: FILESERVER, FILE_SERVER
  - deny pingcap.net hosts except: do.pingcap.net, cla.pingcap.net
EOF
    cat "$REPORT_FILE" >&2
    return 1
}

main() {
    parse_args "$@"
    validate_shas
    init_report_file
    scan_added_diff_lines
    print_policy_report
}

main "$@"
