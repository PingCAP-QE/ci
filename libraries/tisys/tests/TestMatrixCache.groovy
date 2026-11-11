import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses
import static org.junit.Assert.*

/**
 * Table-driven unit tests for matrixCache.groovy utility functions.
 *
 * The functions under test rely on the Jenkins 'env' binding. We mock 'env'
 * with a custom EnvMock class that supports both property access
 * (e.g. env.JENKINS_HOME) and the getEnvironment() method call.
 *
 * Usage:
 *   groovy libraries/tisys/tests/TestMatrixCache.groovy
 */
@RunWith(Suite.class)
@SuiteClasses([
    TestMatrixCache.GenerateContextKey,
    TestMatrixCache.ShouldSkip,
    TestMatrixCache.MarkDone,
    TestMatrixCache.ReplayBypass,
])
class TestMatrixCache {

    // ============================================================
    // Test helpers
    // ============================================================

    /**
     * Mock for the Jenkins 'env' object.
     *
     * Supports:
     *   env.getEnvironment()  → returns the full env map
     *   env.JENKINS_HOME      → returns the JENKINS_HOME value
     *   env.JOB_NAME          → returns the JOB_NAME value
     */
    static class EnvMock {
        private Map<String, String> envMap = [:]
        String JENKINS_HOME = '/tmp/jenkins-cache-test'
        String JOB_NAME = 'test/matrix-job'

        /**
         * Configure the env with the given key-value pairs.
         * Call this after construction, before passing to the script.
         */
        void configure(Map<String, String> env) {
            this.envMap = env
            this.JENKINS_HOME = env['JENKINS_HOME'] ?: '/tmp/jenkins-cache-test'
            this.JOB_NAME = env['JOB_NAME'] ?: 'test/matrix-job'
        }

        def getEnvironment() {
            return envMap
        }
    }

    /**
     * Mock for the Jenkins 'currentBuild' object.
     *
     * Only getBuildCauses() is used by matrixCache. Set failOnCauses to model a
     * Jenkins that cannot expose the causes (old plugin, restricted sandbox).
     */
    static class CurrentBuildMock {
        List causes = []
        boolean failOnCauses = false

        def getBuildCauses() {
            if (failOnCauses) {
                throw new IllegalStateException('build causes are not readable')
            }
            return causes
        }
    }

    /**
     * Load matrixCache.groovy with mocked 'env', plus optional 'params',
     * 'currentBuild' and 'echo' bindings for the replay-bypass tests.
     *
     * stub keys:
     *   params       - Map bound to the pipeline 'params' variable (default [:])
     *   currentBuild - CurrentBuildMock bound to 'currentBuild' (omitted by default)
     *   logs         - List that receives every echo() message
     */
    private static def loadScript(Map<String, String> envMap = [:], Map<String, Object> stub = [:]) {
        def envMock = new EnvMock()
        envMock.configure(envMap)
        def binding = new Binding()
        binding.setVariable('env', envMock)
        binding.setVariable('params', stub.params ?: [:])
        if (stub.containsKey('currentBuild')) {
            binding.setVariable('currentBuild', stub.currentBuild)
        } else {
            // A normal build: no replay cause, so caching stays in effect.
            binding.setVariable('currentBuild', new CurrentBuildMock())
        }
        def logs = stub.logs
        if (logs != null) {
            binding.setVariable('echo', { Object msg -> logs.add(String.valueOf(msg)) })
        }
        def shell = new GroovyShell(binding)
        return shell.parse(new File("libraries/tisys/vars/matrixCache.groovy"))
    }

    // ============================================================
    // _generateContextKey
    // ============================================================
    static class GenerateContextKey {

        private def script

        @Before
        void setUp() {
            // Use an empty env map by default; axis-specific tests override it.
            script = loadScript([:])
        }

        /** Convenience wrapper to call the private _generateContextKey. */
        private String key(Map refs, String stageName, Map extraParams = [:]) {
            return script.invokeMethod('_generateContextKey', [refs, stageName, extraParams])
        }

        @Test
        void shouldProduceValidSha256HashFormat() {
            def k = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc123'], 'build', [:])
            assertNotNull('key should not be null', k)
            assertEquals('SHA-256 hash should be 64 characters', 64, k.length())
            assertTrue('SHA-256 hash should be a hexadecimal string', k ==~ /[a-f0-9]{64}/)
        }

        @Test
        void shouldReturnDeterministicKeyForSameInputs() {
            def cases = [
                [refs: [org: 'pingcap', repo: 'tidb', base_sha: 'abc123', pulls: [[sha: 'pr1']]],
                 stageName: 'build', extraParams: [os: 'linux']],
                [refs: [:], stageName: 'test', extraParams: [:],
                 desc: 'empty refs & no extras'],
                [refs: [org: 'tikv', repo: 'tikv', base_sha: 'sha000'],
                 stageName: 'lint', extraParams: [arch: 'amd64', os: 'linux'],
                 desc: 'maximal refs with extras'],
            ]

            cases.each { c ->
                def k1 = key(c.refs, c.stageName, c.extraParams)
                def k2 = key(c.refs, c.stageName, c.extraParams)
                def label = c.desc ?: "${c.refs}"
                assertEquals("deterministic: ${label}", k1, k2)
            }
        }

        @Test
        void shouldGenerateDifferentKeysForDifferentRefs() {
            def k1 = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc123', pulls: [[sha: 's1']]], 'build', [:])
            def k2 = key([org: 'pingcap', repo: 'tidb', base_sha: 'def456', pulls: [[sha: 's1']]], 'build', [:])
            assertNotEquals('different base_sha should produce different keys', k1, k2)
        }

        @Test
        void shouldGenerateDifferentKeysForDifferentRepos() {
            def k1 = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc123'], 'build', [:])
            def k2 = key([org: 'pingcap', repo: 'tiflash', base_sha: 'abc123'], 'build', [:])
            assertNotEquals('different repo should produce different keys', k1, k2)
        }

        @Test
        void shouldGenerateDifferentKeysForDifferentStageNames() {
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc123']
            def k1 = key(refs, 'build', [:])
            def k2 = key(refs, 'test', [:])
            assertNotEquals('different stageName should produce different keys', k1, k2)
        }

        @Test
        void shouldGenerateDifferentKeysForDifferentExtraParams() {
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc123']
            def k1 = key(refs, 'build', [os: 'linux'])
            def k2 = key(refs, 'build', [os: 'macos'])
            assertNotEquals('different extraParams should produce different keys', k1, k2)
        }

        @Test
        void shouldHandleMultiplePullShas() {
            def k1 = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc', pulls: [[sha: 'sha1']]], 'build', [:])
            def k2 = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc', pulls: [[sha: 'sha1'], [sha: 'sha2']]], 'build', [:])
            assertNotEquals('different pull SHAs should produce different keys', k1, k2)
        }

        @Test
        void shouldUseDefaultsWhenRefsAreEmpty() {
            def k = key([:], 'test', [:])
            assertNotNull('empty refs should still produce a key', k)
            assertTrue('key should be a 64-char hex string', k ==~ /[a-f0-9]{64}/)
        }

        @Test
        void shouldUseUnknownOrgAndRepoWhenMissing() {
            def k = key([base_sha: 'abc'], 'stage', [:])
            assertNotNull('missing org/repo should still produce a key', k)
        }

        @Test
        void shouldUseNoPullsPlaceholderWhenPullsMissing() {
            def k = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc'], 'build', [:])
            assertNotNull('missing pulls should still produce a key', k)
        }

        @Test
        void shouldNotIncorporateMatrixAxisVariablesAutomatically() {
            // Matrix env variables should not affect keys unless callers pass them
            // through extraParams explicitly.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([AXIS_OS: 'linux', AXIS_ARCH: 'amd64'])
            def script2 = loadScript([AXIS_OS: 'linux', AXIS_ARCH: 'arm64'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            assertEquals('different matrix axes should not change key automatically', k1, k2)
        }

        @Test
        void shouldAllowCallerToIncludeMatrixAxisViaExtraParams() {
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script = loadScript([AXIS_OS: 'linux', AXIS_ARCH: 'amd64'])

            def k1 = script.invokeMethod('_generateContextKey', [refs, 'build', [axis_os: 'linux', axis_arch: 'amd64']])
            def k2 = script.invokeMethod('_generateContextKey', [refs, 'build', [axis_os: 'linux', axis_arch: 'arm64']])

            assertNotEquals('callers can control axis-sensitive key parts via extraParams', k1, k2)
        }

        @Test
        void shouldBeIndependentOfJOB_NAMEValue() {
            // JOB_NAME is used only to isolate cache files, not to build key.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([MY_AXIS: 'val', JOB_NAME: 'job/a'])
            def script2 = loadScript([MY_AXIS: 'val', JOB_NAME: 'job/b'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])

            assertEquals('JOB_NAME should not affect generated key', k1, k2)
        }

        @Test
        void shouldSortExtraParamsDeterministically() {
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def k1 = key(refs, 'build', [param_z: 'z', param_a: 'a'])
            def k2 = key(refs, 'build', [param_a: 'a', param_z: 'z'])

            assertEquals('sorted extraParams should produce same key regardless of input order', k1, k2)
        }
    }

    // ============================================================
    // shouldSkip
    // ============================================================
    static class ShouldSkip {

        private File tmpDir
        private String jenkinsHome

        @Before
        void setUp() {
            tmpDir = File.createTempDir()
            jenkinsHome = tmpDir.absolutePath
        }

        @After
        void tearDown() {
            tmpDir.deleteDir()
        }

        /** Build an env map pointing at the temp directory. */
        private Map envFor(String jobName = 'test/matrix-job') {
            return [
                'JENKINS_HOME': jenkinsHome,
                'JOB_NAME'     : jobName,
                'MY_AXIS'      : 'linux',
            ]
        }

        private def load(Map envMap) {
            return loadScript(envMap)
        }

        private Boolean callShouldSkip(def script, Map refs, String stageName, Map extraParams = [:]) {
            return script.invokeMethod('shouldSkip', [refs, stageName, extraParams])
        }

        /** Helper: create a marker file directly for a given script context. */
        private void writeCacheMarker(def script, String key, String content = 'SUCCESS') {
            def jobName = script.binding.getVariable('env').JOB_NAME
            def cacheDir = new File("${jenkinsHome}/matrix-cache/${jobName.replaceAll('/', '_')}")
            cacheDir.mkdirs()
            def markerFile = new File(cacheDir, "${key}.success")
            markerFile.text = content
        }

        @Test
        void shouldReturnFalseWhenCacheFileDoesNotExist() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def result = callShouldSkip(script, refs, 'build', [:])

            assertFalse('should return false when no cache file exists', result)
        }

        @Test
        void shouldReturnTrueWhenKeyIsCachedAsSuccess() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'
            def extraParams = [:]

            // First, determine what the generated key will be
            def key = script.invokeMethod('_generateContextKey', [refs, stageName, extraParams])

            // Write the marker file for this key
            writeCacheMarker(script, key)

            def result = callShouldSkip(script, refs, stageName, extraParams)
            assertTrue('should return true when key is cached as SUCCESS', result)
        }

        @Test
        void shouldReturnFalseWhenKeyIsNotInCache() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Write marker but with a different key
            writeCacheMarker(script, 'some-other-key')

            def result = callShouldSkip(script, refs, stageName, [:])
            assertFalse('should return false when key is not in cache', result)
        }

        @Test
        void shouldReturnTrueWhenMarkerFileExistsRegardlessOfContent() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            def key = script.invokeMethod('_generateContextKey', [refs, stageName, [:]])

            // Marker existence indicates success, regardless of content.
            writeCacheMarker(script, key, 'FAILURE')

            def result = callShouldSkip(script, refs, stageName, [:])
            assertTrue('should return true when marker file exists', result)
        }

        @Test
        void shouldRespectExtraParamsInKeyGeneration() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Cache the key WITH extraParams
            def keyWithExtras = script.invokeMethod('_generateContextKey', [refs, stageName, [os: 'linux']])
            writeCacheMarker(script, keyWithExtras)

            // Check without extraParams — should miss the cache
            def resultWithout = callShouldSkip(script, refs, stageName, [:])
            assertFalse('should miss cache when extraParams differ', resultWithout)

            // Check with the same extraParams — should hit the cache
            def resultWith = callShouldSkip(script, refs, stageName, [os: 'linux'])
            assertTrue('should hit cache when extraParams match', resultWith)
        }

        @Test
        void shouldIsolateByJobName() {
            def env1 = envFor('project/component/job1')
            def env2 = envFor('project/component/job2')

            def script1 = load(env1)
            def script2 = load(env2)

            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            // Write cache for job1 only
            def key1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            writeCacheMarker(script1, key1)

            def result1 = script1.invokeMethod('shouldSkip', [refs, 'build', [:]])
            def result2 = script2.invokeMethod('shouldSkip', [refs, 'build', [:]])

            assertTrue('job1 should find its cache entry', result1)
            assertFalse('job2 should not find job1 cache entry', result2)
        }
    }

    // ============================================================
    // markDone
    // ============================================================
    static class MarkDone {

        private File tmpDir
        private String jenkinsHome

        @Before
        void setUp() {
            tmpDir = File.createTempDir()
            jenkinsHome = tmpDir.absolutePath
        }

        @After
        void tearDown() {
            tmpDir.deleteDir()
        }

        private Map envFor(String jobName = 'test/matrix-job') {
            return [
                'JENKINS_HOME': jenkinsHome,
                'JOB_NAME'     : jobName,
                'MY_AXIS'      : 'linux',
            ]
        }

        private def load(Map envMap) {
            return loadScript(envMap)
        }

        @Test
        void shouldCreateCacheDirectoryAndFile() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            script.invokeMethod('markDone', [refs, stageName, [:]])

            // Verify the cache directory was created
            def cacheDir = new File("${jenkinsHome}/matrix-cache/test_matrix-job")
            assertTrue('cache directory should exist', cacheDir.exists())

            // Verify the marker file was created
            def expectedKey = script.invokeMethod('_generateContextKey', [refs, stageName, [:]])
            def markerFile = new File(cacheDir, "${expectedKey}.success")
            assertTrue('marker file should exist', markerFile.exists())
            assertTrue('marker file should not be empty', markerFile.length() > 0)
        }

        @Test
        void shouldStoreSuccessStatusForKey() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'
            def extraParams = [:]

            script.invokeMethod('markDone', [refs, stageName, extraParams])

            // Determine the expected key
            def expectedKey = script.invokeMethod('_generateContextKey', [refs, stageName, extraParams])

            // Read the marker file
            def markerFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job/${expectedKey}.success")
            assertTrue('marker file should exist', markerFile.exists())
            assertEquals('marker file content should be SUCCESS', 'SUCCESS', markerFile.text)
        }

        @Test
        void shouldAppendToExistingCache() {
            def script = load(envFor())
            def refs1 = [org: 'pingcap', repo: 'tidb', base_sha: 'shaA']
            def refs2 = [org: 'pingcap', repo: 'tidb', base_sha: 'shaB']

            // Mark two different keys
            script.invokeMethod('markDone', [refs1, 'build', [:]])
            script.invokeMethod('markDone', [refs2, 'build', [:]])

            def key1 = script.invokeMethod('_generateContextKey', [refs1, 'build', [:]])
            def key2 = script.invokeMethod('_generateContextKey', [refs2, 'build', [:]])
            def cacheDir = new File("${jenkinsHome}/matrix-cache/test_matrix-job")

            assertTrue('first key marker should exist', new File(cacheDir, "${key1}.success").exists())
            assertTrue('second key marker should exist', new File(cacheDir, "${key2}.success").exists())
            assertEquals('cache should contain exactly 2 marker files', 2, cacheDir.listFiles().size())
        }

        @Test
        void shouldOverwriteExistingKeyWithSameValue() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            // Mark the same key twice
            script.invokeMethod('markDone', [refs, 'build', [:]])
            script.invokeMethod('markDone', [refs, 'build', [:]])

            def expectedKey = script.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def cacheDir = new File("${jenkinsHome}/matrix-cache/test_matrix-job")
            def markerFile = new File(cacheDir, "${expectedKey}.success")

            assertEquals('cache should contain 1 marker file', 1, cacheDir.listFiles().size())
            assertTrue('marker file should exist', markerFile.exists())
            assertEquals('marker file content should be SUCCESS', 'SUCCESS', markerFile.text)
        }

        @Test
        void shouldIsolateCacheByJobName() {
            def script1 = load(envFor('job/a'))
            def script2 = load(envFor('job/b'))
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            script1.invokeMethod('markDone', [refs, 'build', [:]])

            // Job A's cache should exist
            def key = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def cacheFileA = new File("${jenkinsHome}/matrix-cache/job_a/${key}.success")
            assertTrue('job A marker file should exist', cacheFileA.exists())

            // Job B's cache should NOT exist
            def cacheDirB = new File("${jenkinsHome}/matrix-cache/job_b")
            assertFalse('job B cache directory should not exist', cacheDirB.exists())
        }

        @Test
        void shouldHandleMultipleCallsWithDifferentExtraParams() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Mark done for the same refs/stage but different extra params
            script.invokeMethod('markDone', [refs, stageName, [os: 'linux']])
            script.invokeMethod('markDone', [refs, stageName, [os: 'macos']])

            def cacheDir = new File("${jenkinsHome}/matrix-cache/test_matrix-job")
            assertEquals('should have 2 marker files for different extraParams', 2, cacheDir.listFiles().size())

            // Each marker should contain SUCCESS
            cacheDir.listFiles().each { file ->
                assertEquals("marker ${file.name} should be SUCCESS", 'SUCCESS', file.text)
            }
        }
    }

    // ============================================================
    // replay bypass
    // ============================================================

    /**
     * A replay rebuild reuses the JOB_SPEC of the original build, so the cache key
     * is identical and every stage that already succeeded would be skipped. The
     * cache must therefore be bypassed for replay builds.
     */
    static class ReplayBypass {

        private File tmpDir
        private String jenkinsHome
        private List<String> logs

        @Before
        void setUp() {
            tmpDir = File.createTempDir()
            jenkinsHome = tmpDir.absolutePath
            logs = []
        }

        @After
        void tearDown() {
            tmpDir.deleteDir()
        }

        private Map envFor() {
            return [
                'JENKINS_HOME': jenkinsHome,
                'JOB_NAME'     : 'test/matrix-job',
            ]
        }

        /** The cause list Jenkins reports for a replayed build. */
        private static Map replayCause() {
            return [
                '_class'           : 'org.jenkinsci.plugins.workflow.cps.replay.ReplayCause',
                'shortDescription': 'Replay of #42',
            ]
        }

        /** A normal, non-replay cause. */
        private static Map userCause() {
            return [
                '_class'           : 'hudson.model.Cause$UserIdCause',
                'shortDescription': 'Started by user ci-bot',
            ]
        }

        private def load(Map extra = [:]) {
            Map stub = [logs: logs]
            stub.putAll(extra)
            return loadScript(envFor(), stub)
        }

        /** Create the success marker for the given refs/stage, i.e. a cache hit. */
        private void writeCacheMarker(def script, Map refs, String stageName, Map extraParams = [:]) {
            def key = script.invokeMethod('_generateContextKey', [refs, stageName, extraParams])
            def cacheDir = new File("${jenkinsHome}/matrix-cache/test_matrix-job")
            cacheDir.mkdirs()
            new File(cacheDir, "${key}.success").text = 'SUCCESS'
        }

        private Boolean shouldSkip(def script, Map refs, String stageName, Map extraParams = [:]) {
            return script.invokeMethod('shouldSkip', [refs, stageName, extraParams])
        }

        private Boolean isReplayBuild(def script) {
            return script.invokeMethod('isReplayBuild', [])
        }

        private Map baseRefs() {
            return [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
        }

        @Test
        void shouldDetectReplayCause() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [replayCause()])])
            assertTrue('a ReplayCause must make isReplayBuild true', isReplayBuild(script))
        }

        @Test
        void shouldNotDetectReplayForNormalCauses() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [userCause()])])
            assertFalse('a normal cause must not be treated as a replay', isReplayBuild(script))
        }

        @Test
        void shouldNotSkipCachedStageInReplayBuild() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [replayCause()])])
            writeCacheMarker(script, baseRefs(), 'build')

            def result = shouldSkip(script, baseRefs(), 'build')

            assertFalse('a replayed build must not skip a cached stage', result)
        }

        @Test
        void shouldNotSkipCachedStageWhenBypassParamIsTrue() {
            def script = load([params: [MATRIX_CACHE_BYPASS: 'true']])
            writeCacheMarker(script, baseRefs(), 'build')

            def result = shouldSkip(script, baseRefs(), 'build')

            assertFalse('MATRIX_CACHE_BYPASS=true must bypass the cache', result)
        }

        @Test
        void shouldAcceptBypassParamCaseInsensitively() {
            def cases = ['TRUE', 'True', ' true ']

            cases.each { value ->
                def script = load([params: [MATRIX_CACHE_BYPASS: value]])
                writeCacheMarker(script, baseRefs(), 'build')

                assertFalse("bypass value '${value}' should be accepted", shouldSkip(script, baseRefs(), 'build'))
            }
        }

        @Test
        void shouldKeepCacheBehaviourWhenBypassParamIsNotTrue() {
            def cases = ['false', 'yes', '', null]

            cases.each { value ->
                def script = load([params: [MATRIX_CACHE_BYPASS: value]])
                writeCacheMarker(script, baseRefs(), 'build')

                assertTrue("bypass value '${value}' should not bypass the cache",
                    shouldSkip(script, baseRefs(), 'build'))
            }
        }

        @Test
        void shouldKeepCacheBehaviourForNormalBuilds() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [userCause()])])

            assertFalse('an uncached stage must still run', shouldSkip(script, baseRefs(), 'build'))

            writeCacheMarker(script, baseRefs(), 'build')
            assertTrue('a cached stage must still be skipped', shouldSkip(script, baseRefs(), 'build'))
        }

        @Test
        void shouldTreatUnreadableBuildCausesAsNormalBuild() {
            def script = load([currentBuild: new CurrentBuildMock(failOnCauses: true)])
            writeCacheMarker(script, baseRefs(), 'build')

            assertFalse('an unreadable cause list must not be reported as a replay', isReplayBuild(script))
            assertTrue('cache behaviour must be unchanged when causes cannot be read',
                shouldSkip(script, baseRefs(), 'build'))
        }

        @Test
        void shouldWarnWhenBuildCausesCannotBeRead() {
            def script = load([currentBuild: new CurrentBuildMock(failOnCauses: true)])

            isReplayBuild(script)

            assertTrue('an unreadable cause list must be reported in the build log',
                logs.any { it.contains('matrixCache') && it.contains('build causes') })
        }

        @Test
        void shouldReportBypassReasonOncePerBuild() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [replayCause()])])

            shouldSkip(script, baseRefs(), 'build')
            shouldSkip(script, baseRefs(), 'test')

            def bypassLogs = logs.findAll { it.contains('matrix cache bypassed because this is a replay build') }
            assertEquals('the bypass reason must be logged exactly once per build', 1, bypassLogs.size())
        }

        @Test
        void shouldNotReportBypassForNormalBuilds() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [userCause()])])

            shouldSkip(script, baseRefs(), 'build')

            assertTrue('a normal build must not log a bypass', logs.every { !it.contains('matrix cache bypassed') })
        }

        @Test
        void shouldStillWriteMarkerInReplayBuilds() {
            def script = load([currentBuild: new CurrentBuildMock(causes: [replayCause()])])
            def refs = baseRefs()

            script.invokeMethod('markDone', [refs, 'build', [:]])

            def key = script.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def markerFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job/${key}.success")
            assertTrue('markDone must keep writing markers in replay builds', markerFile.exists())
            assertEquals('SUCCESS', markerFile.text)
        }
    }
}
