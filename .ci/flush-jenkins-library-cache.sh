#!/usr/bin/env bash
# Flush the shared-library cache of a Jenkins controller.
#
# A folder-scoped shared library is cached on the controller for
# `refreshTimeMinutes` (60 here) so a merged library change does not take effect
# until the window passes. This script clears that cache on demand, which is the
# same thing the plugin does when the window expires: delete
# `$JENKINS_HOME/global-libraries-cache/<digest>` and its `<digest>-name.txt`
# sibling.
#
# The cache digest is an HMAC of name/version/source, so entries cannot be
# selected by folder — the flush is per library name. Every folder here loads
# `tipipeline@main` from the same repository, so one flush covers them all.
#
# Usage:
#   .ci/flush-jenkins-library-cache.sh --context tke-pingcap-cicd --list
#   .ci/flush-jenkins-library-cache.sh --context tke-pingcap-cicd \
#       --library tipipeline --dry-run
#   .ci/flush-jenkins-library-cache.sh --context tke-pingcap-cicd \
#       --library tipipeline --yes
#
# Deleting an entry a build is copying from fails that build, the same way an
# expiry mid-build does; run this when the controller is quiet.
#
# Security notes for reviewers:
#   - No shell runs inside the controller. Every call is a fixed absolute binary
#     with an argv array, so nothing here can be read as a command, a
#     metacharacter, or a redirection.
#   - The cache directory is a constant; its root comes from the controller's own
#     JENKINS_HOME. The operator never supplies a path.
#   - The only variable path segment is a cache digest, accepted only when it
#     matches ^[0-9a-f]{64}$, so a delete cannot leave the cache directory.
#   - Cluster and pod names are validated before the first kubectl call, and the
#     library name is matched by exact string equality, never by glob.
set -euo pipefail

# Fixed by the plugin; not operator-supplied and not configurable.
CACHE_DIR_NAME="global-libraries-cache"
DIGEST_PATTERN='^[0-9a-f]{64}$'

context=""
namespace="jenkins"
pod="jenkins-0"
container="jenkins"
libraries=()
list_mode=false
dry_run=false
flush_all=false
assume_yes=false

usage() {
    cat <<'USAGE'
Flush a cached shared library on a Jenkins controller.

Options:
  --context <name>     kube context of the cluster running the controller (required)
  --namespace <name>   namespace of the controller (default: jenkins)
  --pod <name>         controller pod (default: jenkins-0; use jenkins-staging-0 for staging)
  --container <name>   container inside the pod (default: jenkins)
  --library <name>     library to flush, e.g. tipipeline (repeatable)
  --all                flush every cached library
  --list               show the cached libraries and exit; deletes nothing
  --dry-run            show what would be removed; deletes nothing
  --yes                do not ask for confirmation
  -h, --help           show this help

Environment:
  KUBECTL              kubectl binary to set (default: kubectl)

Exit codes: 0 success, 1 failure, 2 usage error.
USAGE
}

error() {
    printf 'error: %s\n' "$*" >&2
}

log() {
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2
}

usage_error() {
    error "$*"
    printf 'Run with --help for usage.\n' >&2
    exit 2
}

parse_args() {
    while (($# > 0)); do
        case "$1" in
            --context)
                (($# >= 2)) || usage_error "--context needs a value"
                context="$2"
                shift 2
                ;;
            --namespace)
                (($# >= 2)) || usage_error "--namespace needs a value"
                namespace="$2"
                shift 2
                ;;
            --pod)
                (($# >= 2)) || usage_error "--pod needs a value"
                pod="$2"
                shift 2
                ;;
            --container)
                (($# >= 2)) || usage_error "--container needs a value"
                container="$2"
                shift 2
                ;;
            --library)
                (($# >= 2)) || usage_error "--library needs a value"
                libraries+=("$2")
                shift 2
                ;;
            --all)
                flush_all=true
                shift
                ;;
            --list)
                list_mode=true
                shift
                ;;
            --dry-run)
                dry_run=true
                shift
                ;;
            --yes | -y)
                assume_yes=true
                shift
                ;;
            -h | --help)
                usage
                exit 0
                ;;
            *)
                usage_error "unknown argument: $1"
                ;;
        esac
    done
}

validate() {
    [[ -n "${context}" ]] || usage_error "--context is required: name the cluster explicitly"

    local value
    for value in "${context}" "${namespace}" "${pod}" "${container}"; do
        [[ "${value}" =~ ^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$ ]] ||
            usage_error "not a valid kube name: ${value}"
    done

    if [[ "${flush_all}" == true && ${#libraries[@]} -gt 0 ]]; then
        usage_error "--all and --library are mutually exclusive"
    fi
    if [[ "${list_mode}" != true && "${flush_all}" != true && ${#libraries[@]} -eq 0 ]]; then
        usage_error "nothing to flush: pass --library <name>, --all, or --list"
    fi

    local library
    for library in ${libraries[@]+"${libraries[@]}"}; do
        [[ "${library}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
            usage_error "not a valid library name: ${library}"
    done
}

# One command inside the controller: a fixed absolute binary plus an argv array.
kexec() {
    "${KUBECTL:-kubectl}" --context "${context}" -n "${namespace}" \
        exec "${pod}" -c "${container}" -- "$@"
}

# The cache root, from the controller's own environment. Never from the operator.
resolve_cache_root() {
    local home
    home="$(kexec /usr/bin/printenv JENKINS_HOME 2>/dev/null || true)"
    if [[ -z "${home}" ]]; then
        home="/var/jenkins_home"
    fi
    if [[ "${home}" != /* || "${home}" == *..* ]]; then
        error "unexpected JENKINS_HOME on the controller: ${home}"
        return 1
    fi
    printf '%s/%s' "${home}" "${CACHE_DIR_NAME}"
}

# Cached libraries as "<digest>\t<library>", newest first. A cache entry the
# plugin did not write is skipped rather than passed to a delete.
read_entries() {
    local cache_root="$1"
    local listing name_file digest library

    if ! listing="$(kexec /bin/ls -1t -- "${cache_root}" 2>&1)"; then
        error "cannot read ${cache_root} on the controller: ${listing}"
        return 1
    fi

    while IFS= read -r name_file; do
        case "${name_file}" in
            *-name.txt) ;;
            *) continue ;;
        esac
        digest="${name_file%-name.txt}"
        if [[ ! "${digest}" =~ ${DIGEST_PATTERN} ]]; then
            printf 'warning: skipping unexpected entry %s\n' "${name_file}" >&2
            continue
        fi
        library="$(kexec /bin/cat -- "${cache_root}/${name_file}" 2>/dev/null || true)"
        if [[ -z "${library}" ]]; then
            printf 'warning: skipping unreadable entry %s\n' "${name_file}" >&2
            continue
        fi
        printf '%s\t%s\n' "${digest}" "${library}"
    done <<<"${listing}"
}

selected() {
    local library="$1"
    [[ "${flush_all}" == true ]] && return 0
    local candidate
    for candidate in ${libraries[@]+"${libraries[@]}"}; do
        [[ "${candidate}" == "${library}" ]] && return 0
    done
    return 1
}

confirm() {
    [[ "${assume_yes}" == true ]] && return 0
    [[ "${dry_run}" == true ]] && return 0
    [[ "${list_mode}" == true ]] && return 0

    if [[ ! -t 0 ]]; then
        error "refusing to delete cache entries without --yes outside a terminal"
        exit 1
    fi

    local target
    if [[ "${flush_all}" == true ]]; then
        target="every cached library"
    else
        target="${libraries[*]}"
    fi
    local answer
    read -r -p "Delete the cache for ${target} on ${context}/${namespace} (pod ${pod})? [y/N] " answer
    [[ "${answer}" == [yY] || "${answer}" == [yY][eE][sS] ]] || {
        echo "cancelled." >&2
        exit 0
    }
}

main() {
    parse_args "$@"
    validate

    local cache_root entries
    if ! cache_root="$(resolve_cache_root)"; then
        exit 1
    fi

    log "controller: ${context} (namespace ${namespace}, pod ${pod}, container ${container})"

    confirm

    if ! entries="$(read_entries "${cache_root}")"; then
        exit 1
    fi

    local total=0 removed=0 matched=0 failed=0
    local digest library

    while IFS=$'\t' read -r digest library; do
        [[ -n "${digest}" ]] || continue
        total=$((total + 1))

        if [[ "${list_mode}" == true ]]; then
            printf '%-20s %s\n' "${library}" "${digest}"
            continue
        fi

        selected "${library}" || continue
        matched=$((matched + 1))

        if [[ "${dry_run}" == true ]]; then
            printf 'would remove: %s %s\n' "${library}" "${digest}"
            continue
        fi
        if kexec /bin/rm -rf -- "${cache_root}/${digest}" &&
            kexec /bin/rm -f -- "${cache_root}/${digest}-name.txt"; then
            printf 'removed: %s %s\n' "${library}" "${digest}"
            removed=$((removed + 1))
        else
            printf 'error: could not remove %s\n' "${digest}" >&2
            failed=$((failed + 1))
        fi
    done <<<"${entries}"

    if [[ "${list_mode}" == true ]]; then
        printf '%s cached entr(ies).\n' "${total}"
    elif [[ "${dry_run}" == true ]]; then
        printf 'would remove %s of %s cached entr(ies).\n' "${matched}" "${total}"
    else
        printf 'removed %s of %s cached entr(ies).\n' "${removed}" "${total}"
        log "the next build re-caches any library whose cache entry was removed"
    fi

    [[ "${failed}" -eq 0 ]] || exit 5
}

main "$@"
