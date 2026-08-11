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
#   BAZEL_ENSURE_TMP_DIR         "true" to create the bazel tmp dir
#   BAZEL_TMP_DIR                bazel tmp dir (default: /home/jenkins/.tidb/tmp)
#   BAZEL_REPOSITORY_CACHE_PATH  shared repository cache dir, empty to keep
#                                the default path (default: empty)
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

if [ "${BAZEL_ENSURE_TMP_DIR:-false}" = "true" ]; then
    mkdir -p "${BAZEL_TMP_DIR:-/home/jenkins/.tidb/tmp}"
fi

# Prefer shared local repository cache when writable, fallback to default path.
if [ -n "${BAZEL_REPOSITORY_CACHE_PATH:-}" ]; then
    if [ "${BAZEL_REPOSITORY_CACHE_GUARD:-true}" = "true" ]; then
        if [ -d "${BAZEL_REPOSITORY_CACHE_PATH}" ] && mkdir -p "${BAZEL_REPOSITORY_CACHE_PATH}/content_addressable/sha256" 2>/dev/null; then
            "${SED_I[@]}" "s|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=${BAZEL_REPOSITORY_CACHE_PATH}|g" Makefile.common
            echo "using shared bazel repository cache: ${BAZEL_REPOSITORY_CACHE_PATH}"
        else
            echo "shared bazel repository cache unavailable or not writable, keep repository_cache=/home/jenkins/.tidb/tmp"
        fi
    else
        "${SED_I[@]}" "s|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=${BAZEL_REPOSITORY_CACHE_PATH}|g" Makefile.common
    fi
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
