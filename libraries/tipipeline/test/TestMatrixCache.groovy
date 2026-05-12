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
 *   groovy libraries/tipipeline/test/TestMatrixCache.groovy
 */
@RunWith(Suite.class)
@SuiteClasses([
    TestMatrixCache.GenerateContextKey,
    TestMatrixCache.ShouldSkip,
    TestMatrixCache.MarkDone,
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
     * Load matrixCache.groovy with a mocked 'env' binding.
     */
    private static def loadScript(Map<String, String> envMap = [:]) {
        def envMock = new EnvMock()
        envMock.configure(envMap)
        def binding = new Binding()
        binding.setVariable('env', envMock)
        def shell = new GroovyShell(binding)
        return shell.parse(new File("libraries/tipipeline/vars/matrixCache.groovy"))
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
        void shouldProduceValidMD5HashFormat() {
            def k = key([org: 'pingcap', repo: 'tidb', base_sha: 'abc123'], 'build', [:])
            assertNotNull('key should not be null', k)
            assertEquals('MD5 hash should be 32 characters', 32, k.length())
            assertTrue('MD5 hash should be a hexadecimal string', k ==~ /[a-f0-9]{32}/)
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
            assertTrue('key should be a 32-char hex string', k ==~ /[a-f0-9]{32}/)
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
        void shouldIncorporateMatrixAxisVariables() {
            // Scripts loaded with different axis envs must produce different keys
            // even when all other inputs are identical.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([AXIS_OS: 'linux', AXIS_ARCH: 'amd64'])
            def script2 = loadScript([AXIS_OS: 'linux', AXIS_ARCH: 'arm64'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            assertNotEquals('different matrix axes should produce different keys', k1, k2)
        }

        @Test
        void shouldIgnoreJenkinsDefaultEnvVars() {
            // Default vars like WORKSPACE, HOME, PATH, BUILD_NUMBER, JOB_NAME
            // should be filtered out and not affect the key.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([MY_AXIS: 'val1', WORKSPACE: '/ws', HOME: '/home', PATH: '/bin', BUILD_NUMBER: '42', JOB_NAME: 'job'])
            def script2 = loadScript([MY_AXIS: 'val1'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            assertEquals('default env vars should be filtered out', k1, k2)
        }

        @Test
        void shouldBeIndependentOfJOB_NAMEValue() {
            // JOB_NAME is filtered out from env.getEnvironment() results,
            // so it should NOT affect the generated key.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([MY_AXIS: 'val', JOB_NAME: 'job/a'])
            def script2 = loadScript([MY_AXIS: 'val', JOB_NAME: 'job/b'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])

            assertEquals('JOB_NAME should be filtered out', k1, k2)
        }

        @Test
        void shouldSortMatrixContextDeterministically() {
            // Matrix axis variables should be sorted alphabetically so that
            // the same set produces the same key regardless of insertion order.
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def script1 = loadScript([AXIS_Z: 'z', AXIS_A: 'a'])
            def script2 = loadScript([AXIS_A: 'a', AXIS_Z: 'z'])

            def k1 = script1.invokeMethod('_generateContextKey', [refs, 'build', [:]])
            def k2 = script2.invokeMethod('_generateContextKey', [refs, 'build', [:]])

            assertEquals('sorted env should produce same key regardless of input order', k1, k2)
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

        /** Helper: write cache content directly for a given script context. */
        private void writeCache(def script, Map data) {
            def jobName = script.binding.getVariable('env').JOB_NAME
            def cacheFile = new File("${jenkinsHome}/matrix-cache/${jobName.replaceAll('/', '_')}.json")
            cacheFile.parentFile.mkdirs()
            cacheFile.text = groovy.json.JsonOutput.toJson(data)
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

            // Write the cache with this key marked as SUCCESS
            writeCache(script, [(key): 'SUCCESS'])

            def result = callShouldSkip(script, refs, stageName, extraParams)
            assertTrue('should return true when key is cached as SUCCESS', result)
        }

        @Test
        void shouldReturnFalseWhenKeyIsNotInCache() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Write cache but with a different key
            writeCache(script, ['some-other-key': 'SUCCESS'])

            def result = callShouldSkip(script, refs, stageName, [:])
            assertFalse('should return false when key is not in cache', result)
        }

        @Test
        void shouldReturnFalseWhenCacheValueIsNotSuccess() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            def key = script.invokeMethod('_generateContextKey', [refs, stageName, [:]])

            // Write cache with a non-SUCCESS value for the key
            writeCache(script, [(key): 'FAILURE'])

            def result = callShouldSkip(script, refs, stageName, [:])
            assertFalse('should return false when cached value is not "SUCCESS"', result)
        }

        @Test
        void shouldReturnFalseWhenCacheIsCorrupt() {
            def script = load(envFor())
            def jobName = script.binding.getVariable('env').JOB_NAME
            def cacheFile = new File("${jenkinsHome}/matrix-cache/${jobName.replaceAll('/', '_')}.json")
            cacheFile.parentFile.mkdirs()
            cacheFile.text = 'this is not valid json'

            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            def result = callShouldSkip(script, refs, 'build', [:])
            assertFalse('should return false when cache is corrupt', result)
        }

        @Test
        void shouldRespectExtraParamsInKeyGeneration() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Cache the key WITH extraParams
            def keyWithExtras = script.invokeMethod('_generateContextKey', [refs, stageName, [os: 'linux']])
            writeCache(script, [(keyWithExtras): 'SUCCESS'])

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
            def cacheDir = new File("${jenkinsHome}/matrix-cache")
            cacheDir.mkdirs()
            new File(cacheDir, 'project_component_job1.json').text =
                groovy.json.JsonOutput.toJson([(key1): 'SUCCESS'])

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
            def cacheDir = new File("${jenkinsHome}/matrix-cache")
            assertTrue('cache directory should exist', cacheDir.exists())

            // Verify the cache file was created
            def cacheFile = new File(cacheDir, 'test_matrix-job.json')
            assertTrue('cache file should exist', cacheFile.exists())
            assertTrue('cache file should not be empty', cacheFile.length() > 0)
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

            // Read the cache file
            def cacheFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job.json")
            def data = new groovy.json.JsonSlurper().parse(cacheFile)

            assertEquals('cached value should be SUCCESS', 'SUCCESS', data[expectedKey])
        }

        @Test
        void shouldAppendToExistingCache() {
            def script = load(envFor())
            def refs1 = [org: 'pingcap', repo: 'tidb', base_sha: 'shaA']
            def refs2 = [org: 'pingcap', repo: 'tidb', base_sha: 'shaB']

            // Mark two different keys
            script.invokeMethod('markDone', [refs1, 'build', [:]])
            script.invokeMethod('markDone', [refs2, 'build', [:]])

            def cacheFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job.json")
            def data = new groovy.json.JsonSlurper().parse(cacheFile)

            def key1 = script.invokeMethod('_generateContextKey', [refs1, 'build', [:]])
            def key2 = script.invokeMethod('_generateContextKey', [refs2, 'build', [:]])

            assertEquals('first key should be SUCCESS', 'SUCCESS', data[key1])
            assertEquals('second key should be SUCCESS', 'SUCCESS', data[key2])
            assertEquals('cache should contain exactly 2 entries', 2, data.size())
        }

        @Test
        void shouldOverwriteExistingKeyWithSameValue() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            // Mark the same key twice
            script.invokeMethod('markDone', [refs, 'build', [:]])
            script.invokeMethod('markDone', [refs, 'build', [:]])

            def cacheFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job.json")
            def data = new groovy.json.JsonSlurper().parse(cacheFile)

            assertEquals('cache should contain 1 entry', 1, data.size())
            assertEquals('value should be SUCCESS', 'SUCCESS', data.values().first())
        }

        @Test
        void shouldIsolateCacheByJobName() {
            def script1 = load(envFor('job/a'))
            def script2 = load(envFor('job/b'))
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']

            script1.invokeMethod('markDone', [refs, 'build', [:]])

            // Job A's cache should exist
            def cacheFileA = new File("${jenkinsHome}/matrix-cache/job_a.json")
            assertTrue('job A cache file should exist', cacheFileA.exists())

            // Job B's cache should NOT exist
            def cacheFileB = new File("${jenkinsHome}/matrix-cache/job_b.json")
            assertFalse('job B cache file should not exist', cacheFileB.exists())
        }

        @Test
        void shouldHandleMultipleCallsWithDifferentExtraParams() {
            def script = load(envFor())
            def refs = [org: 'pingcap', repo: 'tidb', base_sha: 'abc']
            def stageName = 'build'

            // Mark done for the same refs/stage but different extra params
            script.invokeMethod('markDone', [refs, stageName, [os: 'linux']])
            script.invokeMethod('markDone', [refs, stageName, [os: 'macos']])

            def cacheFile = new File("${jenkinsHome}/matrix-cache/test_matrix-job.json")
            def data = new groovy.json.JsonSlurper().parse(cacheFile)

            assertEquals('should have 2 entries for different extraParams', 2, data.size())

            // Each entry should be SUCCESS
            data.each { k, v ->
                assertEquals("key ${k} should be SUCCESS", 'SUCCESS', v)
            }
        }
    }
}
