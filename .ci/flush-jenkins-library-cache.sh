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
set -euo pipefail

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
  KUBECTL              kubectl binary to use (default: kubectl)

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
        # The name reaches a shell inside the pod and selects files by exact
        # match, so anything outside this set is refused rather than quoted.
        [[ "${library}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
            usage_error "not a valid library name: ${library}"
    done
}

# The half that runs inside the controller. Positional arguments: mode
# (list|flush), dry_run (true|false), flush_all (true|false), then library names.
remote_script() {
    cat <<'REMOTE'
set -uo pipefail

mode="$1"
dry_run="$2"
flush_all="$3"
shift 3

cache_root="${JENKINS_HOME:-/var/jenkins_home}/global-libraries-cache"
if [ ! -d "${cache_root}" ]; then
    echo "error: ${cache_root} does not exist on this controller" >&2
    exit 4
fi
cd "${cache_root}" || exit 4

wanted=" "
for library in "$@"; do
    wanted="${wanted}${library} "
done

entries=0
matched=0
removed=0
failed=0
for name_file in *-name.txt; do
    [ -f "${name_file}" ] || continue
    entries=$((entries + 1))

    library="$(cat "${name_file}" 2>/dev/null || true)"
    digest="${name_file%-name.txt}"
    if [ -z "${library}" ] || [ -z "${digest}" ]; then
        echo "warning: skipping unreadable cache entry ${name_file}" >&2
        continue
    fi
    case "${digest}" in
        */*) echo "warning: skipping unexpected cache entry ${name_file}" >&2; continue ;;
    esac

    if [ "${mode}" = list ]; then
        mtime="$(date -r "${digest}" '+%Y-%m-%d %H:%M' 2>/dev/null || echo '?')"
        printf '%-20s %s  cached %s\n' "${library}" "${digest}" "${mtime}"
        continue
    fi

    selected=false
    if [ "${flush_all}" = true ]; then
        selected=true
    else
        case "${wanted}" in
            *" ${library} "*) selected=true ;;
        esac
    fi
    [ "${selected}" = true ] || continue
    matched=$((matched + 1))

    if [ "${dry_run}" = true ]; then
        echo "would remove: ${library} ${digest}"
        continue
    fi
    if rm -rf -- "${digest}" && rm -f -- "${name_file}"; then
        echo "removed: ${library} ${digest}"
        removed=$((removed + 1))
    else
        echo "error: could not remove ${digest}" >&2
        failed=$((failed + 1))
    fi
done

if [ "${mode}" = list ]; then
    echo "${entries} cached entr(ies)."
elif [ "${dry_run}" = true ]; then
    echo "would remove ${matched} of ${entries} cached entr(ies)."
else
    echo "removed ${removed} of ${entries} cached entr(ies)."
fi
[ "${failed}" -eq 0 ] || exit 5
REMOTE
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

    local kubectl="${KUBECTL:-kubectl}"
    local mode="flush"
    [[ "${list_mode}" == true ]] && mode="list"

    confirm

    log "controller: ${context} (namespace ${namespace}, pod ${pod}, container ${container})"

    local remote
    remote="$(remote_script)"

    if ! "${kubectl}" --context "${context}" -n "${namespace}" \
        exec "${pod}" -c "${container}" -- \
        bash -c "${remote}" flush "${mode}" "${dry_run}" "${flush_all}" \
        ${libraries[@]+"${libraries[@]}"}; then
        error "flush failed on ${context} (see the message above)"
        exit 1
    fi

    if [[ "${mode}" == "flush" && "${dry_run}" != true ]]; then
        log "the next build re-caches any library whose cache entry was removed"
    fi
}

main "$@"
