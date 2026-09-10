#!/usr/bin/env bash
# Prepare a checked-out bazel workspace for CI builds.
#
# Centralizes the legacy per-pipeline "Hotfix bazel deps/cache (temporary)"
# logic. Configuration is passed through environment variables set by the
# bazel.groovy shared library:
#
#   BAZEL_STRIP_URLS             sed -E alternation of legacy cache/mirror
#                                URLs to remove from WORKSPACE/DEPS.bzl
#   BAZEL_PATCH_CHECK_TARGET     "true" to drop check-bazel-prepare from the
#                                Makefile "check:" target (default: true)
#   BAZEL_TMP_DIR                bazel output root and repository cache
#                                parent (default: ${WORKSPACE}/.cache/bazel)
#   BAZEL_ENSURE_TMP_DIR         "true" to create the bazel tmp dir
#   BAZEL_REPOSITORY_CACHE_PATH  shared repository cache dir, empty to use
#                                ${BAZEL_TMP_DIR}/repository_cache
#   BAZEL_REPOSITORY_CACHE_GUARD "true" to only use the shared cache when it
#                                is writable (default: true)
#   BAZEL_REMOTE_CACHE_MODE      "disable" to turn remote cache off in
#                                .bazelrc, "set" to point it at
#                                BAZEL_REMOTE_CACHE_URL (default: empty)
#   BAZEL_REMOTE_CACHE_URL       cache service URL used with mode "set"
#   BAZEL_GUARDED                "true" to skip everything when no stale URL
#                                is present (used after workspace cache
#                                restore in matrix stages)
set -euxo pipefail

# Portable in-place sed (GNU and BSD).
if sed --version >/dev/null 2>&1; then
    SED_I=(sed -i)
else
    SED_I=(sed -i '')
fi

# Redirect bazel's output root and repository cache off the node-local
# /home/jenkins/.tidb disk, which bazel builds can exhaust quickly. Prefer an
# explicit BAZEL_TMP_DIR, then the large mounted Jenkins workspace volume.
BAZEL_OUTPUT_ROOT="${BAZEL_TMP_DIR:-}"
if [ -z "${BAZEL_OUTPUT_ROOT}" ] && [ -n "${WORKSPACE:-}" ]; then
    BAZEL_OUTPUT_ROOT="${WORKSPACE}/.cache/bazel"
fi

if [ -n "${BAZEL_OUTPUT_ROOT}" ]; then
    mkdir -p "${BAZEL_OUTPUT_ROOT}"
    for f in Makefile.common Makefile; do
        [ -f "$f" ] || continue
        "${SED_I[@]}" "s|--output_user_root=/home/jenkins/.tidb/tmp|--output_user_root=${BAZEL_OUTPUT_ROOT}|g" "$f"
    done
elif [ "${BAZEL_ENSURE_TMP_DIR:-false}" = "true" ]; then
    mkdir -p /home/jenkins/.tidb/tmp
fi

# Resolve the repository cache: an opt-in shared cache first, otherwise a
# workspace-local directory so the node disk is not filled.
BAZEL_REPO_CACHE="${BAZEL_REPOSITORY_CACHE_PATH:-}"
if [ -n "${BAZEL_REPO_CACHE}" ] && [ "${BAZEL_REPOSITORY_CACHE_GUARD:-true}" = "true" ]; then
    if [ -d "${BAZEL_REPO_CACHE}" ] && mkdir -p "${BAZEL_REPO_CACHE}/content_addressable/sha256" 2>/dev/null; then
        echo "using shared bazel repository cache: ${BAZEL_REPO_CACHE}"
    else
        echo "shared bazel repository cache unavailable or not writable, falling back"
        BAZEL_REPO_CACHE=""
    fi
fi
if [ -z "${BAZEL_REPO_CACHE}" ] && [ -n "${BAZEL_OUTPUT_ROOT}" ]; then
    BAZEL_REPO_CACHE="${BAZEL_OUTPUT_ROOT}/repository_cache"
    mkdir -p "${BAZEL_REPO_CACHE}"
fi
if [ -n "${BAZEL_REPO_CACHE}" ]; then
    for f in Makefile.common Makefile; do
        [ -f "$f" ] || continue
        "${SED_I[@]}" "s|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=${BAZEL_REPO_CACHE}|g" "$f"
    done
fi

if [ -z "${BAZEL_STRIP_URLS:-}" ]; then
    echo "No stale bazel cache URLs configured (BAZEL_STRIP_URLS empty), skip cleanup"
    exit 0
fi

if [ "${BAZEL_GUARDED:-false}" = "true" ] && ! grep -qE "${BAZEL_STRIP_URLS}" WORKSPACE DEPS.bzl 2>/dev/null; then
    echo "No legacy bazel deps URL found in WORKSPACE/DEPS.bzl, skip cleanup"
    exit 0
fi

# Clean legacy cache/mirror URLs that are unstable outside the legacy environment.
for f in WORKSPACE DEPS.bzl; do
    [ -f "$f" ] || continue
    "${SED_I[@]}" -E "/${BAZEL_STRIP_URLS}/d" "$f"
done

# Avoid "check" targets re-writing legacy cache settings during replay validation.
if [ "${BAZEL_PATCH_CHECK_TARGET:-true}" = "true" ]; then
    "${SED_I[@]}" 's/^check: check-bazel-prepare /check: /' Makefile || true
fi

# Remote cache handling in .bazelrc.
if [ -n "${BAZEL_REMOTE_CACHE_MODE:-}" ]; then
    if [ -f .bazelrc ]; then
        "${SED_I[@]}" '/^try-import \/data\/bazel$/d' .bazelrc
        case "${BAZEL_REMOTE_CACHE_MODE}" in
            disable)
                for scope in build test run; do
                    grep -q "^${scope} --noremote_accept_cached$" .bazelrc || echo "${scope} --noremote_accept_cached" >> .bazelrc
                    grep -q "^${scope} --noremote_upload_local_results$" .bazelrc || echo "${scope} --noremote_upload_local_results" >> .bazelrc
                done
                ;;
            set)
                if [ -z "${BAZEL_REMOTE_CACHE_URL:-}" ]; then
                    echo "BAZEL_REMOTE_CACHE_URL is required when BAZEL_REMOTE_CACHE_MODE=set" >&2
                    exit 1
                fi
                "${SED_I[@]}" '/^build --remote_cache=/d; /^test --remote_cache=/d; /^run --remote_cache=/d' .bazelrc
                for scope in build test run; do
                    echo "${scope} --remote_cache=${BAZEL_REMOTE_CACHE_URL}" >> .bazelrc
                done
                ;;
            *)
                echo "unsupported BAZEL_REMOTE_CACHE_MODE: ${BAZEL_REMOTE_CACHE_MODE}" >&2
                exit 1
                ;;
        esac
    fi
fi

# Verify no legacy bazel cache/mirror URLs remain.
grep -nE "${BAZEL_STRIP_URLS}" WORKSPACE DEPS.bzl || true
grep -n '^check:' Makefile | head -n 3 || true
