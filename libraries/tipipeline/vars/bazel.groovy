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
// The actual workspace manipulation lives in the plain shell script
// scripts/pingcap/tidb/prepare-bazel-workspace.sh (available in the
// agent workspace via the ci repo checkout), so no fragile shell
// escaping is needed in Groovy. Configuration flows to the script
// through BAZEL_* env vars computed by workspaceEnv().
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

// Compute the BAZEL_* environment map consumed by resources/bazel/prepare-workspace.sh.
//
// opts:
//   cloud:            override cloud env name (default: CI_CLOUD_ENV or 'gcp')
//   stripUrls:        override stale URLs to remove (default: envConfig)
//   patchCheckTarget: patch Makefile "check:" target (default: true)
//   remoteCache:      null, [mode: 'disable'] or [mode: 'set', url: '...'] (default: envConfig)
//   repositoryCache:  null, '/path' or [path: '/path', guard: true|false] (default: envConfig)
//   ensureTmpDir:     create the bazel tmp dir (default: false)
def workspaceEnv(Map opts = [:]) {
    def cfg = envConfig(opts.cloud)
    def stripUrls = opts.containsKey('stripUrls') ? opts.stripUrls : cfg.stripUrls
    def patchCheckTarget = opts.containsKey('patchCheckTarget') ? opts.patchCheckTarget : true
    def ensureTmpDir = opts.containsKey('ensureTmpDir') ? opts.ensureTmpDir : false
    def repositoryCache = opts.containsKey('repositoryCache') ? opts.repositoryCache : (cfg.repositoryCachePath ? [path: cfg.repositoryCachePath, guard: true] : null)
    if (repositoryCache instanceof String) {
        repositoryCache = [path: repositoryCache, guard: true]
    }
    def remoteCache = opts.containsKey('remoteCache') ? opts.remoteCache : cfg.remoteCache
    def remoteMode = remoteCache?.mode?.trim() ?: ''
    if (remoteMode == 'set' && !(remoteCache.url?.trim())) {
        throw new Exception('remoteCache mode "set" requires a url')
    }
    if (remoteMode != '' && remoteMode != 'disable' && remoteMode != 'set') {
        throw new Exception("unsupported remoteCache mode: ${remoteMode}")
    }

    return [
        'BAZEL_STRIP_URLS': stripUrls ? stripPattern(stripUrls) : '',
        'BAZEL_PATCH_CHECK_TARGET': patchCheckTarget ? 'true' : 'false',
        'BAZEL_ENSURE_TMP_DIR': ensureTmpDir ? 'true' : 'false',
        'BAZEL_REPOSITORY_CACHE_PATH': repositoryCache ? repositoryCache.path : '',
        'BAZEL_REPOSITORY_CACHE_GUARD': repositoryCache ? ((repositoryCache.containsKey('guard') ? repositoryCache.guard : true) ? 'true' : 'false') : '',
        'BAZEL_REMOTE_CACHE_MODE': remoteMode,
        'BAZEL_REMOTE_CACHE_URL': remoteMode == 'set' ? remoteCache.url : '',
    ]
}

// Run the workspace preparation script. Must be called inside the repo dir.
def prepareWorkspace(Map opts = [:]) {
    runBazelScript('prepare-bazel-workspace.sh', workspaceEnv(opts))
}

// Lightweight cleanup for matrix test stages, where the workspace is
// restored from cache and may contain stale URLs again. Must be called
// inside the repo dir.
def reapplyStaleUrlCleanup(Map opts = [:]) {
    def env = workspaceEnv(opts)
    env['BAZEL_GUARDED'] = 'true'
    runBazelScript('prepare-bazel-workspace.sh', env)
}

// Run the workspace preparation script with the given BAZEL_* env vars.
// Must be called inside the repo dir; the script is looked up in the ci
// repo checkout in the agent workspace.
def runBazelScript(String scriptName, Map bazelEnv) {
    withEnv(bazelEnv.collect { key, value -> "${key}=${value}" }) {
        sh "bash \${WORKSPACE}/scripts/pingcap/tidb/${scriptName}"
    }
}
