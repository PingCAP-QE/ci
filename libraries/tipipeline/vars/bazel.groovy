// bazel.groovy
//
// Shared helpers to prepare a checked-out bazel workspace for CI builds.
//
// Background: when jobs migrated from AWS to GCP, the legacy bazel
// cache/mirror endpoints (bazel-cache.pingcap.net:8080, ats.apps.svc,
// cache.hawkingrei.com, mirror.bazel.build) became unreachable/unstable.
// Previously every pipeline carried its own "Hotfix bazel deps/cache
// (temporary)" stage duplicating the same logic. These helpers centralize
// that logic and make the cache endpoints configurable per cloud
// environment, so the same job definition can run on any cloud.
//
// The cloud environment is selected through the CI_CLOUD_ENV env var
// (injected globally by Jenkins), defaulting to 'gcp'. To onboard a new
// cloud, add an entry to envConfig() and adjust the pod templates'
// cache mounts; job definitions do not need to change.

// Resolve the cloud environment name from the CI_CLOUD_ENV env var.
def currentEnv() {
    try {
        return env.CI_CLOUD_ENV?.trim() ?: null
    } catch (Exception e) {
        return null
    }
}

// Per-cloud bazel cache configuration.
//
// Fields:
//   stripUrls:           legacy cache/mirror URLs to remove from WORKSPACE/DEPS.bzl
//   remoteCache:         null, [mode: 'disable'] or [mode: 'set', url: '...']
//   repositoryCachePath: shared local repository cache path or null
def envConfig(String cloud = null) {
    def resolved = cloud?.trim() ?: (currentEnv() ?: 'gcp')
    switch (resolved) {
        case 'gcp':
            return [
                stripUrls: [
                    'bazel-cache.pingcap.net:8080',
                    'ats.apps.svc',
                    'cache.hawkingrei.com',
                    'mirror.bazel.build',
                ],
                // Mainline gcp jobs do not override .bazelrc remote cache.
                remoteCache: null,
                // Shared repository cache is opt-in per job.
                repositoryCachePath: null,
            ]
        default:
            throw new Exception("unsupported bazel cloud env: ${resolved}, please add it to bazel.envConfig()")
    }
}

// Build a sed -E alternation pattern from a list of cache/mirror URLs.
def stripPattern(List<String> urls) {
    return urls.collect { it.replaceAll(/\./, '[.]') }.join('|')
}

// Generate the script that cleans legacy bazel cache/mirror references
// from the checked out workspace. Run it inside the repo dir.
//
// opts:
//   cloud:            override cloud env name (default: CI_CLOUD_ENV or 'gcp')
//   stripUrls:        override stale URLs to remove (default: envConfig)
//   patchCheckTarget: patch Makefile "check:" target to drop check-bazel-prepare (default: true)
//   remoteCache:      null, [mode: 'disable'] or [mode: 'set', url: '...'] (default: envConfig)
//   repositoryCache:  null, '/path' or [path: '/path', guard: true|false] (default: envConfig)
//   ensureTmpDir:     create /home/jenkins/.tidb/tmp (default: false)
def buildWorkspaceScript(Map opts = [:]) {
    def cfg = envConfig(opts.cloud)
    def stripUrls = opts.containsKey('stripUrls') ? opts.stripUrls : cfg.stripUrls
    def patchCheckTarget = opts.containsKey('patchCheckTarget') ? opts.patchCheckTarget : true
    def remoteCache = opts.containsKey('remoteCache') ? opts.remoteCache : cfg.remoteCache
    def repositoryCache = opts.containsKey('repositoryCache') ? opts.repositoryCache : (cfg.repositoryCachePath ? [path: cfg.repositoryCachePath, guard: true] : null)
    def ensureTmpDir = opts.containsKey('ensureTmpDir') ? opts.ensureTmpDir : false

    if (repositoryCache instanceof String) {
        repositoryCache = [path: repositoryCache, guard: true]
    }

    def lines = []
    lines << '#!/usr/bin/env bash'
    lines << 'set -euxo pipefail'

    if (stripUrls) {
        def pattern = stripPattern(stripUrls)
        lines << '# Clean legacy bazel cache/mirror URLs that are unstable outside the legacy environment.'
        lines << 'for f in WORKSPACE DEPS.bzl; do'
        lines << '  [ -f "$f" ] || continue'
        lines << "  sed -i -E '/${pattern}/d' \"\$f\""
        lines << 'done'

        if (patchCheckTarget) {
            lines << '# Avoid "check" targets re-writing legacy cache settings during replay validation.'
            lines << "sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true"
        }

        if (ensureTmpDir) {
            lines << '# Ensure expected bazel tmp dir exists after mount point change.'
            lines << 'mkdir -p /home/jenkins/.tidb/tmp'
        }

        if (repositoryCache) {
            if (repositoryCache.guard) {
                lines << '# Prefer shared local repository cache when writable, fallback to default path.'
                lines << "if [ -d ${repositoryCache.path} ] && mkdir -p ${repositoryCache.path}/content_addressable/sha256 2>/dev/null; then"
                lines << "  sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=${repositoryCache.path}|g' Makefile.common"
                lines << "  echo \"using shared bazel repository cache: ${repositoryCache.path}\""
                lines << 'else'
                lines << '  echo "shared bazel repository cache unavailable or not writable, keep repository_cache=/home/jenkins/.tidb/tmp"'
                lines << 'fi'
            } else {
                lines << "sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=${repositoryCache.path}|g' Makefile.common"
            }
        }

        if (remoteCache) {
            switch (remoteCache.mode) {
                case 'disable':
                    lines << '# Disable remote cache usage for this migration replay path.'
                    lines << 'if [ -f .bazelrc ]; then'
                    lines << "  sed -i '/^try-import \u005C/data\u005C/bazel\$/d' .bazelrc"
                    ['build', 'test', 'run'].each { scope ->
                        lines << "  grep -q '^${scope} --noremote_accept_cached\$' .bazelrc || echo '${scope} --noremote_accept_cached' >> .bazelrc"
                        lines << "  grep -q '^${scope} --noremote_upload_local_results\$' .bazelrc || echo '${scope} --noremote_upload_local_results' >> .bazelrc"
                    }
                    lines << 'fi'
                    break
                case 'set':
                    def url = remoteCache.url?.trim()
                    if (!url) {
                        throw new Exception('remoteCache mode "set" requires a url')
                    }
                    lines << '# Point bazel remote cache at the cloud cache service.'
                    lines << 'if [ -f .bazelrc ]; then'
                    lines << "  sed -i '/^try-import \u005C/data\u005C/bazel\$/d' .bazelrc"
                    lines << "  sed -i '/^build --remote_cache=/d; /^test --remote_cache=/d; /^run --remote_cache=/d' .bazelrc"
                    ['build', 'test', 'run'].each { scope ->
                        lines << "  echo '${scope} --remote_cache=${url}' >> .bazelrc"
                    }
                    lines << 'fi'
                    break
                default:
                    throw new Exception("unsupported remoteCache mode: ${remoteCache.mode}")
            }
        }

        lines << '# Verify no legacy bazel cache/mirror URLs remain.'
        lines << "grep -nE '${pattern}' WORKSPACE DEPS.bzl || true"
        lines << "grep -n '^check:' Makefile | head -n 3 || true"
    } else {
        lines << "echo 'No stale bazel cache URLs configured (stripUrls empty), skip cleanup'"
    }

    return lines.join('\n')
}

// Run the workspace preparation script. Must be called inside the repo dir.
def prepareWorkspace(Map opts = [:]) {
    sh script: buildWorkspaceScript(opts), label: 'prepare bazel workspace'
}

// Generate the lightweight cleanup script used inside matrix test stages,
// where the workspace is restored from cache and may contain stale URLs
// again. Run it inside the repo dir.
//
// opts:
//   cloud:     override cloud env name (default: CI_CLOUD_ENV or 'gcp')
//   stripUrls: override stale URLs to remove (default: envConfig)
def buildStaleUrlCleanupScript(Map opts = [:]) {
    def cfg = envConfig(opts.cloud)
    def stripUrls = opts.containsKey('stripUrls') ? opts.stripUrls : cfg.stripUrls

    if (!stripUrls) {
        return '#!/usr/bin/env bash\nset -euxo pipefail\necho "No stale bazel cache URLs configured, skip cleanup"'
    }
    def pattern = stripPattern(stripUrls)
    return """#!/usr/bin/env bash
set -euxo pipefail
if grep -qE '${pattern}' WORKSPACE DEPS.bzl 2>/dev/null; then
  for f in WORKSPACE DEPS.bzl; do
    [ -f "\$f" ] || continue
    sed -i -E '/${pattern}/d' "\$f"
  done
  sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true
else
  echo "No legacy bazel deps URL found in WORKSPACE/DEPS.bzl."
fi
"""
}

// Run the lightweight stale URL cleanup. Must be called inside the repo dir.
def reapplyStaleUrlCleanup(Map opts = [:]) {
    sh script: buildStaleUrlCleanupScript(opts), label: 're-apply bazel deps URL cleanup'
}
