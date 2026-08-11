import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Table-driven unit tests for bazel.groovy utility functions.
 *
 * Usage:
 *   groovy libraries/tipipeline/tests/TestBazel.groovy
 */
@RunWith(Enclosed.class)
class TestBazel {
    private static def loadScript() {
        new GroovyShell().parse(
            new File("libraries/tipipeline/vars/bazel.groovy"))
    }

    private static String captureException(Closure closure) {
        try {
            closure()
        } catch (Exception e) {
            return e.message
        }
        fail('expected an exception to be thrown')
        return null
    }

    // ============================================================
    // stripPattern
    // ============================================================
    static class StripPattern {
        private def stripPattern

        @Before
        void setUp() {
            def script = loadScript()
            stripPattern = { List<String> urls ->
                script.invokeMethod('stripPattern', [urls] as Object[])
            }
        }

        @Test
        void shouldEscapeDotsAndJoinWithAlternation() {
            def cases = [
                [urls: ['bazel-cache.pingcap.net:8080', 'ats.apps.svc', 'cache.hawkingrei.com', 'mirror.bazel.build'],
                 expected: 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build'],
                [urls: ['a.b.c'],
                 expected: 'a[.]b[.]c'],
            ]
            cases.each { c ->
                assert c.expected == stripPattern(c.urls) : "stripPattern(${c.urls}) should be '${c.expected}'"
            }
        }

        @Test
        void shouldReturnEmptyStringForEmptyList() {
            assert stripPattern([]) == ''
        }
    }

    // ============================================================
    // envConfig
    // ============================================================
    static class EnvConfig {
        private def envConfig

        @Before
        void setUp() {
            def script = loadScript()
            envConfig = { String cloud ->
                script.invokeMethod('envConfig', [cloud])
            }
        }

        @Test
        void shouldReturnGcpConfigWithLegacyStripUrls() {
            def cfg = envConfig('gcp')
            assert cfg.stripUrls == ['bazel-cache.pingcap.net:8080', 'ats.apps.svc', 'cache.hawkingrei.com', 'mirror.bazel.build'] :
                "gcp stripUrls should contain the 4 legacy URLs, got ${cfg.stripUrls}"
            assert cfg.remoteCache == null : 'gcp remoteCache should be null by default'
            assert cfg.repositoryCachePath == null : 'gcp repositoryCachePath should be null by default'
        }

        @Test
        void shouldThrowOnUnknownCloud() {
            def msg = captureException {
                envConfig('azure')
            }
            assert msg.contains('unsupported bazel cloud env: azure') : "unexpected error message: ${msg}"
        }
    }

    // ============================================================
    // buildWorkspaceScript
    // ============================================================
    static class BuildWorkspaceScript {
        private def buildWorkspaceScript

        @Before
        void setUp() {
            def script = loadScript()
            buildWorkspaceScript = { Map opts ->
                script.invokeMethod('buildWorkspaceScript', [opts])
            }
        }

        @Test
        void shouldEmitDefaultGcpCleanup() {
            def script = buildWorkspaceScript([cloud: 'gcp'])
            assert script.startsWith('#!/usr/bin/env bash') : 'should start with bash shebang'
            assert script.contains('set -euxo pipefail')
            assert script.contains('bazel-cache[.]pingcap[.]net:8080') : 'should strip legacy bazel-cache URL'
            assert script.contains('ats[.]apps[.]svc')
            assert script.contains('cache[.]hawkingrei[.]com')
            assert script.contains('mirror[.]bazel[.]build')
            assert script.contains('for f in WORKSPACE DEPS.bzl; do')
            assert script.contains("sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d'")
            assert script.contains("sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true")
            assert script.contains("grep -nE 'bazel-cache[.]pingcap[.]net:8080")
            assert script.contains("grep -n '^check:' Makefile | head -n 3 || true")
            assert !script.contains('noremote_accept_cached') : 'gcp default should not disable remote cache'
            assert !script.contains('repository_cache=') : 'gcp default should not switch repository cache'
            assert !script.contains('mkdir -p /home/jenkins/.tidb/tmp') : 'gcp default should not create tmp dir'
        }

        @Test
        void shouldSkipMakefilePatchWhenDisabled() {
            def script = buildWorkspaceScript([cloud: 'gcp', patchCheckTarget: false])
            assert !script.contains('check-bazel-prepare') : 'should skip Makefile check target patch'
        }

        @Test
        void shouldUseCustomStripUrls() {
            def script = buildWorkspaceScript([cloud: 'gcp', stripUrls: ['cache.newcloud.example:8080']])
            assert script.contains('cache[.]newcloud[.]example:8080') : 'should strip custom URL'
            assert !script.contains('bazel-cache[.]pingcap[.]net') : 'should not contain default URLs when overridden'
        }

        @Test
        void shouldSkipStripBlocksForEmptyUrls() {
            def script = buildWorkspaceScript([cloud: 'gcp', stripUrls: []])
            assert script.contains('No stale bazel cache URLs configured')
            assert !script.contains('sed -i -E')
        }

        @Test
        void shouldDisableRemoteCache() {
            def script = buildWorkspaceScript([cloud: 'gcp', remoteCache: [mode: 'disable']])
            assert script.contains("sed -i '/^try-import \u005C/data\u005C/bazel\$/d' .bazelrc")
            ['build', 'test', 'run'].each { scope ->
                assert script.contains("grep -q '^${scope} --noremote_accept_cached\$' .bazelrc || echo '${scope} --noremote_accept_cached' >> .bazelrc") :
                    "should disable accept cached for ${scope}"
                assert script.contains("grep -q '^${scope} --noremote_upload_local_results\$' .bazelrc || echo '${scope} --noremote_upload_local_results' >> .bazelrc") :
                    "should disable upload local results for ${scope}"
            }
            assert script.contains('if [ -f .bazelrc ]; then') : 'should guard .bazelrc edits'
        }

        @Test
        void shouldSetRemoteCacheUrl() {
            def script = buildWorkspaceScript([cloud: 'gcp', remoteCache: [mode: 'set', url: 'https://cache.azure.example:8443']])
            assert script.contains("sed -i '/^build --remote_cache=/d; /^test --remote_cache=/d; /^run --remote_cache=/d' .bazelrc")
            ['build', 'test', 'run'].each { scope ->
                assert script.contains("echo '${scope} --remote_cache=https://cache.azure.example:8443' >> .bazelrc") :
                    "should set remote cache for ${scope}"
            }
        }

        @Test
        void shouldRequireUrlForSetMode() {
            def msg = captureException {
                buildWorkspaceScript([cloud: 'gcp', remoteCache: [mode: 'set']])
            }
            assert msg.contains('remoteCache mode "set" requires a url') : "unexpected error message: ${msg}"
        }

        @Test
        void shouldRejectUnknownRemoteCacheMode() {
            def msg = captureException {
                buildWorkspaceScript([cloud: 'gcp', remoteCache: [mode: 'bogus']])
            }
            assert msg.contains('unsupported remoteCache mode: bogus') : "unexpected error message: ${msg}"
        }

        @Test
        void shouldSwitchRepositoryCacheWithGuard() {
            def script = buildWorkspaceScript([cloud: 'gcp', repositoryCache: [path: '/share/.cache/bazel-repository-cache', guard: true]])
            assert script.contains('if [ -d /share/.cache/bazel-repository-cache ] && mkdir -p /share/.cache/bazel-repository-cache/content_addressable/sha256 2>/dev/null; then')
            assert script.contains("sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common")
            assert script.contains('else')
            assert script.contains('fi')
        }

        @Test
        void shouldSwitchRepositoryCacheWithoutGuard() {
            def script = buildWorkspaceScript([cloud: 'gcp', repositoryCache: [path: '/share/.cache/bazel-repository-cache', guard: false]])
            assert script.contains("sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common")
            assert !script.contains('if [ -d /share/.cache/bazel-repository-cache ]') : 'should not guard when guard disabled'
        }

        @Test
        void shouldAcceptRepositoryCacheAsString() {
            def script = buildWorkspaceScript([cloud: 'gcp', repositoryCache: '/share/.cache/bazel-repository-cache'])
            assert script.contains('if [ -d /share/.cache/bazel-repository-cache ]') : 'string path should default to guarded'
        }

        @Test
        void shouldCreateTmpDirWhenRequested() {
            def script = buildWorkspaceScript([cloud: 'gcp', ensureTmpDir: true])
            assert script.contains('mkdir -p /home/jenkins/.tidb/tmp')
        }
    }

    // ============================================================
    // buildStaleUrlCleanupScript
    // ============================================================
    static class BuildStaleUrlCleanupScript {
        private def buildStaleUrlCleanupScript

        @Before
        void setUp() {
            def script = loadScript()
            buildStaleUrlCleanupScript = { Map opts ->
                script.invokeMethod('buildStaleUrlCleanupScript', [opts])
            }
        }

        @Test
        void shouldEmitGuardedReapply() {
            def script = buildStaleUrlCleanupScript([cloud: 'gcp'])
            assert script.contains("if grep -qE 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build' WORKSPACE DEPS.bzl 2>/dev/null; then")
            assert script.contains('for f in WORKSPACE DEPS.bzl; do')
            assert script.contains("sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d'")
            assert script.contains("sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true")
            assert script.contains('else')
            assert script.contains('echo "No legacy bazel deps URL found in WORKSPACE/DEPS.bzl."')
        }

        @Test
        void shouldSkipForEmptyUrls() {
            def script = buildStaleUrlCleanupScript([cloud: 'gcp', stripUrls: []])
            assert script.contains('No stale bazel cache URLs configured')
            assert !script.contains('grep -qE')
        }
    }
}
