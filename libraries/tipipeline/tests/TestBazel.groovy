import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Table-driven unit tests for bazel.groovy utility functions and the
 * resources/bazel/prepare-workspace.sh script.
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
    // workspaceEnv
    // ============================================================
    static class WorkspaceEnv {
        private def workspaceEnv

        @Before
        void setUp() {
            def script = loadScript()
            workspaceEnv = { Map opts ->
                script.invokeMethod('workspaceEnv', [opts])
            }
        }

        @Test
        void shouldEmitDefaultGcpEnv() {
            def env = workspaceEnv([cloud: 'gcp'])
            assert env.BAZEL_STRIP_URLS == 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build' :
                "unexpected strip urls: ${env.BAZEL_STRIP_URLS}"
            assert env.BAZEL_PATCH_CHECK_TARGET == 'true' : 'should patch check target by default'
            assert env.BAZEL_ENSURE_TMP_DIR == 'false' : 'should not create tmp dir by default'
            assert env.BAZEL_REPOSITORY_CACHE_PATH == '' : 'should not switch repository cache by default'
            assert env.BAZEL_REPOSITORY_CACHE_GUARD == '' : 'guard should be empty without a path'
            assert env.BAZEL_REMOTE_CACHE_MODE == '' : 'should not touch remote cache by default'
            assert env.BAZEL_REMOTE_CACHE_URL == '' : 'remote cache url should be empty by default'
        }

        @Test
        void shouldSkipMakefilePatchWhenDisabled() {
            def env = workspaceEnv([cloud: 'gcp', patchCheckTarget: false])
            assert env.BAZEL_PATCH_CHECK_TARGET == 'false'
        }

        @Test
        void shouldUseCustomStripUrls() {
            def env = workspaceEnv([cloud: 'gcp', stripUrls: ['cache.newcloud.example:8080']])
            assert env.BAZEL_STRIP_URLS == 'cache[.]newcloud[.]example:8080'
        }

        @Test
        void shouldEmptyStripUrlsForEmptyList() {
            def env = workspaceEnv([cloud: 'gcp', stripUrls: []])
            assert env.BAZEL_STRIP_URLS == ''
        }

        @Test
        void shouldDisableRemoteCache() {
            def env = workspaceEnv([cloud: 'gcp', remoteCache: [mode: 'disable']])
            assert env.BAZEL_REMOTE_CACHE_MODE == 'disable'
            assert env.BAZEL_REMOTE_CACHE_URL == ''
        }

        @Test
        void shouldSetRemoteCacheUrl() {
            def env = workspaceEnv([cloud: 'gcp', remoteCache: [mode: 'set', url: 'https://cache.azure.example:8443']])
            assert env.BAZEL_REMOTE_CACHE_MODE == 'set'
            assert env.BAZEL_REMOTE_CACHE_URL == 'https://cache.azure.example:8443'
        }

        @Test
        void shouldRequireUrlForSetMode() {
            def msg = captureException {
                workspaceEnv([cloud: 'gcp', remoteCache: [mode: 'set']])
            }
            assert msg.contains('remoteCache mode "set" requires a url') : "unexpected error message: ${msg}"
        }

        @Test
        void shouldRejectUnknownRemoteCacheMode() {
            def msg = captureException {
                workspaceEnv([cloud: 'gcp', remoteCache: [mode: 'bogus']])
            }
            assert msg.contains('unsupported remoteCache mode: bogus') : "unexpected error message: ${msg}"
        }

        @Test
        void shouldAcceptRepositoryCacheAsString() {
            def env = workspaceEnv([cloud: 'gcp', repositoryCache: '/share/.cache/bazel-repository-cache'])
            assert env.BAZEL_REPOSITORY_CACHE_PATH == '/share/.cache/bazel-repository-cache'
            assert env.BAZEL_REPOSITORY_CACHE_GUARD == 'true' : 'string path should default to guarded'
        }

        @Test
        void shouldDisableRepositoryCacheGuard() {
            def env = workspaceEnv([cloud: 'gcp', repositoryCache: [path: '/share/.cache/bazel-repository-cache', guard: false]])
            assert env.BAZEL_REPOSITORY_CACHE_PATH == '/share/.cache/bazel-repository-cache'
            assert env.BAZEL_REPOSITORY_CACHE_GUARD == 'false'
        }

        @Test
        void shouldCreateTmpDirWhenRequested() {
            def env = workspaceEnv([cloud: 'gcp', ensureTmpDir: true])
            assert env.BAZEL_ENSURE_TMP_DIR == 'true'
        }
    }

    // ============================================================
    // scripts/pingcap/tidb/prepare-bazel-workspace.sh (functional)
    // ============================================================
    static class PrepareWorkspaceScript {
        private static final File RESOURCE = new File('scripts/pingcap/tidb/prepare-bazel-workspace.sh')

        private File workDir
        private File makefile
        private File workspaceFile
        private File depsFile

        private static File createWorkDir() {
            def dir = new File(System.getProperty('java.io.tmpdir'),
                "bazel-prepare-test-${System.nanoTime()}")
            dir.mkdirs()
            return dir
        }

        @Before
        void setUp() {
            workDir = createWorkDir()
            workspaceFile = new File(workDir, 'WORKSPACE')
            workspaceFile.text = 'urls = ["https://bazel-cache.pingcap.net:8080/a", "https://mirror.bazel.build/b"]\n'
            depsFile = new File(workDir, 'DEPS.bzl')
            depsFile.text = 'DEPS = "https://cache.hawkingrei.com/x"\n'
            makefile = new File(workDir, 'Makefile')
            makefile.text = 'check: check-bazel-prepare all\n'
        }

        @After
        void tearDown() {
            workDir.deleteDir()
        }

        private String runScript(Map<String, String> env) {
            def builder = new ProcessBuilder('bash', RESOURCE.absolutePath)
            builder.directory(workDir)
            builder.environment().putAll(env)
            builder.environment().remove('BAZEL_GUARDED')
            def proc = builder.start()
            def out = proc.inputStream.text
            def err = proc.errorStream.text
            def exit = proc.waitFor()
            if (exit != 0) {
                fail("script failed (exit ${exit})\nstdout:\n${out}\nstderr:\n${err}")
            }
            return out
        }

        private String stripPattern() {
            return 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build'
        }

        @Test
        void shouldStripLegacyUrlsAndPatchCheckTarget() {
            runScript([BAZEL_STRIP_URLS: stripPattern()])
            assert !workspaceFile.text.contains('bazel-cache.pingcap.net') : 'WORKSPACE should not contain legacy URL'
            assert !depsFile.text.contains('cache.hawkingrei.com') : 'DEPS.bzl should not contain legacy URL'
            assert makefile.text == 'check: all\n' : "Makefile check target should drop check-bazel-prepare, got: ${makefile.text}"
        }

        @Test
        void shouldSkipMakefilePatchWhenDisabled() {
            runScript([BAZEL_STRIP_URLS: stripPattern(), BAZEL_PATCH_CHECK_TARGET: 'false'])
            assert makefile.text == 'check: check-bazel-prepare all\n' : 'Makefile should be untouched'
        }

        @Test
        void shouldSkipWhenEmptyStripUrls() {
            runScript([BAZEL_STRIP_URLS: ''])
            assert workspaceFile.text.contains('bazel-cache.pingcap.net') : 'nothing should be stripped'
            assert makefile.text.contains('check-bazel-prepare') : 'Makefile should be untouched'
        }

        @Test
        void shouldSkipCleanupInGuardedModeWhenNoStaleUrls() {
            def dir = createWorkDir()
            try {
                def wf = new File(dir, 'WORKSPACE')
                wf.text = 'urls = ["https://example.com/clean"]\n'
                def mf = new File(dir, 'Makefile')
                mf.text = 'check: check-bazel-prepare all\n'
                def builder = new ProcessBuilder('bash', RESOURCE.absolutePath)
                builder.directory(dir)
                builder.environment().putAll([BAZEL_STRIP_URLS: stripPattern(), BAZEL_GUARDED: 'true'])
                def proc = builder.start()
                def exit = proc.waitFor()
                assert exit == 0 : "script failed: ${proc.errorStream.text}"
                assert wf.text.contains('example.com/clean') : 'clean workspace should be untouched'
                assert mf.text.contains('check-bazel-prepare') : 'Makefile should be untouched in guarded skip'
            } finally {
                dir.deleteDir()
            }
        }

        @Test
        void shouldCleanupInGuardedModeWhenStaleUrlsPresent() {
            runScript([BAZEL_STRIP_URLS: stripPattern(), BAZEL_GUARDED: 'true'])
            def dir = createWorkDir()
            try {
                def wf = new File(dir, 'WORKSPACE')
                wf.text = 'urls = ["https://bazel-cache.pingcap.net:8080/a"]\n'
                new File(dir, 'Makefile').text = 'check: check-bazel-prepare all\n'
                def builder = new ProcessBuilder('bash', RESOURCE.absolutePath)
                builder.directory(dir)
                builder.environment().putAll([BAZEL_STRIP_URLS: stripPattern(), BAZEL_GUARDED: 'true'])
                def proc = builder.start()
                def exit = proc.waitFor()
                assert exit == 0 : "script failed: ${proc.errorStream.text}"
                assert !wf.text.contains('bazel-cache.pingcap.net') : 'stale URL should be removed'
                assert new File(dir, 'Makefile').text == 'check: all\n' : 'Makefile should be patched'
            } finally {
                dir.deleteDir()
            }
        }

        @Test
        void shouldDisableRemoteCacheInBazelrc() {
            def bazelrc = new File(workDir, '.bazelrc')
            bazelrc.text = 'try-import /data/bazel\n'
            runScript([BAZEL_STRIP_URLS: stripPattern(), BAZEL_REMOTE_CACHE_MODE: 'disable'])
            def content = bazelrc.text
            assert !content.contains('try-import /data/bazel') : 'try-import should be removed'
            ['build', 'test', 'run'].each { scope ->
                assert content.contains("${scope} --noremote_accept_cached") : "missing ${scope} --noremote_accept_cached"
                assert content.contains("${scope} --noremote_upload_local_results") : "missing ${scope} --noremote_upload_local_results"
            }
        }

        @Test
        void shouldSetRemoteCacheUrlInBazelrc() {
            def bazelrc = new File(workDir, '.bazelrc')
            bazelrc.text = 'build --remote_cache=old://cache\n'
            runScript([BAZEL_STRIP_URLS: stripPattern(), BAZEL_REMOTE_CACHE_MODE: 'set',
                       BAZEL_REMOTE_CACHE_URL: 'https://cache.azure.example:8443'])
            def content = bazelrc.text
            assert !content.contains('old://cache') : 'old remote cache url should be removed'
            ['build', 'test', 'run'].each { scope ->
                assert content.contains("${scope} --remote_cache=https://cache.azure.example:8443") :
                    "missing ${scope} --remote_cache url"
            }
        }

        @Test
        void shouldUseSharedRepositoryCacheWhenWritable() {
            def shared = new File(workDir, 'share-cache')
            shared.mkdirs()
            def makefileCommon = new File(workDir, 'Makefile.common')
            makefileCommon.text = 'repository_cache=/home/jenkins/.tidb/tmp\n'
            runScript([BAZEL_STRIP_URLS: stripPattern(),
                       BAZEL_REPOSITORY_CACHE_PATH: shared.absolutePath])
            assert makefileCommon.text.contains("repository_cache=${shared.absolutePath}") :
                "Makefile.common should point at shared cache, got: ${makefileCommon.text}"
        }

        @Test
        void shouldKeepDefaultRepositoryCacheWhenSharedUnavailable() {
            def makefileCommon = new File(workDir, 'Makefile.common')
            makefileCommon.text = 'repository_cache=/home/jenkins/.tidb/tmp\n'
            runScript([BAZEL_STRIP_URLS: stripPattern(),
                       BAZEL_REPOSITORY_CACHE_PATH: '/nonexistent-share-cache'])
            assert makefileCommon.text.contains('repository_cache=/home/jenkins/.tidb/tmp') :
                'Makefile.common should keep the default path'
        }

        @Test
        void shouldSwitchRepositoryCacheWithoutGuard() {
            def makefileCommon = new File(workDir, 'Makefile.common')
            makefileCommon.text = 'repository_cache=/home/jenkins/.tidb/tmp\n'
            runScript([BAZEL_STRIP_URLS: stripPattern(),
                       BAZEL_REPOSITORY_CACHE_PATH: '/nonexistent-share-cache',
                       BAZEL_REPOSITORY_CACHE_GUARD: 'false'])
            assert makefileCommon.text.contains('repository_cache=/nonexistent-share-cache') :
                'unguarded mode should switch unconditionally'
        }

        @Test
        void shouldCreateTmpDirWhenRequested() {
            def tmpDir = new File(workDir, 'tmp-dir')
            runScript([BAZEL_STRIP_URLS: stripPattern(), BAZEL_ENSURE_TMP_DIR: 'true', BAZEL_TMP_DIR: tmpDir.absolutePath])
            assert tmpDir.isDirectory() : 'tmp dir should be created'
        }
    }
}
