import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Table-driven unit tests for the Jenkins-independent parts of prow.groovy.
 *
 * Usage:
 *   groovy libraries/tipipeline/tests/TestProw.groovy
 */
@RunWith(Enclosed.class)
class TestProw {
    private static def loadProw(Map steps = [:]) {
        def binding = new Binding()
        steps.each { name, value -> binding.setVariable(name, value) }
        new GroovyShell(binding).parse(
            new File("libraries/tipipeline/vars/prow.groovy"))
    }

    private static def refs() {
        [
            org: 'pingcap',
            repo: 'tidb',
            base_ref: 'main',
            base_sha: 'abcdef1234567890',
            pulls: [[number: 123, sha: '1234567890abcdef']],
        ]
    }

    static class Syntax {
        @Test
        void shouldLoadProwSharedLibrary() {
            loadProw()
        }

        @Test
        void shouldProvideGitAskPassAsLibraryResource() {
            assertTrue(
                'Git askpass helper must be under the Jenkins library resources directory',
                new File('libraries/tipipeline/resources/scripts/git_askpass.sh').isFile())
        }
    }

    static class CacheKeys {
        private def script

        @Before
        void setUp() {
            script = loadProw()
        }

        @Test
        void shouldBuildPullRequestCacheKeys() {
            def pullRefs = refs()

            assertEquals(
                'git/pingcap/tidb/rev-abcdef1-1234567',
                script.getCacheKey('git', pullRefs))
            assertEquals([
                'git/pingcap/tidb/rev-abcdef1',
                'git/pingcap/tidb/rev-',
            ], script.getRestoreKeys('git', pullRefs))
        }

        @Test
        void shouldBuildBranchCacheKeysWithoutPullRequests() {
            def branchRefs = refs() + [pulls: []]

            assertEquals(
                'git/pingcap/tidb/rev-abcdef1',
                script.getCacheKey('git', branchRefs))
            assertEquals(
                ['git/pingcap/tidb/rev-'],
                script.getRestoreKeys('git', branchRefs))
        }
    }

    static class PublicCheckout {
        @Test
        void shouldUsePublicHttpsCheckoutUrlAndPullRefspec() {
            def shCalls = []
            def script = loadProw(sh: { Map args -> shCalls << args })

            script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')

            assertEquals('one checkout shell call', 1, shCalls.size())
            def checkoutScript = shCalls[0].script
            assertTrue(
                checkoutScript.contains(
                    'git config remote.origin.url https://github.example/pingcap/tidb.git'))
            assertTrue(
                checkoutScript.contains(
                    '+refs/heads/main:refs/remotes/origin/main'))
            assertTrue(
                checkoutScript.contains(
                    '+refs/pull/123/head:refs/remotes/origin/pr/123/head'))
        }

        @Test
        void shouldKeepRefsDirNonEmptyForStashTransfer() {
            def shCalls = []
            def script = loadProw(sh: { Map args -> shCalls << args })

            script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')

            def checkoutScript = shCalls[0].script
            assertTrue(
                'checkout must leave a file under .git/refs so the directory survives stash/unstash',
                checkoutScript.contains('mkdir -p .git/refs'))
            assertTrue(
                'checkout must leave a file under .git/refs so the directory survives stash/unstash',
                checkoutScript.contains('touch .git/refs/.keep'))
        }
    }

    static class PrivateCheckout {
        @Test
        void shouldSkipGithubHostKeyScanWhenCdnIsEnabled() {
            def events = []
            def script = loadProw(
                env: [GIT_CDN_ENABLED: 'true'],
                sshagent: { Map args, Closure body ->
                    events << "sshagent:${args.credentials}".toString()
                    body()
                },
                sh: { Map args -> events << (args.script ?: '').toString() },
            )

            script.checkoutPrivateRefs(
                refs(), 'github-sre-bot-ssh', 7, false, 'github.com')

            assertEquals(
                'sshagent:[github-sre-bot-ssh]', events[0])
            assertTrue(
                events[1].contains(
                    'git config remote.origin.url git@github.com:pingcap/tidb.git'))
            assertTrue(
                events.every { !it.contains('ssh-keyscan') })
        }
    }

    static class AskPass {
        @Test
        void shouldScopeCredentialsAndCleanUpAskPassScript() {
            def events = []
            def script = loadProw(
                env: [WORKSPACE: '/tmp/ws'],
                sh: { Map args -> events << "sh:${args.script}".toString() },
                libraryResource: { String path ->
                    events << "resource:${path}".toString()
                    'askpass helper'
                },
                writeFile: { Map args ->
                    events << "write:${args.file}:${args.text}".toString()
                },
                usernamePassword: { Map args -> args },
                withCredentials: { List credentials, Closure body ->
                    events << "credentials:${credentials[0].credentialsId}".toString()
                    body()
                },
                withEnv: { List environment, Closure body ->
                    events << "env:${environment}".toString()
                    body()
                },
            )

            script.withGitAskPass('github-bot-https') {
                events << 'checkout'
            }

            assertEquals('resource loaded', 'resource:scripts/git_askpass.sh', events[0])
            assertTrue('helper written from resource',
                events[1] ==~ /write:\/tmp\/ws\/git-askpass-.*:askpass helper/)
            assertTrue('helper made executable',
                events[2] ==~ /sh:chmod 700 '\/tmp\/ws\/git-askpass-.*'/)
            assertEquals('credentials scoped', 'credentials:github-bot-https', events[3])
            assertTrue('askpass env scoped',
                events[4] ==~ /env:\[GIT_ASKPASS=\/tmp\/ws\/git-askpass-.*, GIT_TERMINAL_PROMPT=0\]/)
            assertEquals('checkout runs inside scope', 'checkout', events[5])
            assertTrue('helper removed',
                events[6] ==~ /sh:rm -f '\/tmp\/ws\/git-askpass-.*'/)
        }

        @Test
        void shouldPreserveAskPassCreationFailureWithoutCleanup() {
            def events = []
            def failure = new RuntimeException('temporary directory is unavailable')
            def script = loadProw(
                env: [WORKSPACE: '/tmp/ws'],
                libraryResource: { String path -> throw failure },
                sh: { Map args -> events << "sh:${args.script}".toString() },
            )

            try {
                script.withGitAskPass('github-bot-https') {
                    fail('checkout should not run when askpass creation fails')
                }
                fail('askpass creation should fail')
            } catch (RuntimeException actual) {
                assertSame(failure, actual)
            }

            assertTrue('cleanup must not run without a temporary path', events.isEmpty())
        }
    }

    static class WithCache {
        private List events
        private boolean markerExists = false
        private String markerContent = ''

        private def load(Map overrides = [:]) {
            events = []
            def steps = [
                cache: { Map args, Closure body ->
                    events << ['cache', args]
                    body()
                },
                fileExists: { Map a ->
                    events << ['fileExists', a.file]
                    return markerExists
                },
                readFile: { Map a ->
                    events << ['readFile', a.file]
                    return markerContent
                },
                writeFile: { Map a -> events << ['writeFile', a.file, a.text] },
                echo: { String message -> events << ['echo', message] },
            ]
            steps.putAll(overrides)
            return loadProw(steps)
        }

        @Test
        void shouldRunBodyAndWriteMarkerOnMiss() {
            def script = load()
            def ran = false

            script.withCache(path: './archives', key: 'k1') { ran = true }

            assertTrue('body runs on a cache miss', ran)
            def cacheArgs = events.find { it[0] == 'cache' }[1]
            assertEquals('cache path forwarded', './archives', cacheArgs.path)
            assertEquals('cache key forwarded', 'k1', cacheArgs.key)
            assertEquals('marker force-included', '**/*,.cache-complete', cacheArgs.includes)
            assertEquals('restoreKeys defaults to empty', [], cacheArgs.restoreKeys)
            def write = events.find { it[0] == 'writeFile' }
            assertEquals('marker written inside path', './archives/.cache-complete', write[1])
            assertEquals('marker content is the exact key', 'k1', write[2])
        }

        @Test
        void shouldSkipBodyWhenMarkerMatchesKey() {
            markerExists = true
            markerContent = 'k1\n'
            def script = load()
            def ran = false

            script.withCache(path: './archives', key: 'k1') { ran = true }

            assertFalse('body skipped on an exact-key hit', ran)
            assertNull('marker not rewritten on a hit', events.find { it[0] == 'writeFile' })
        }

        @Test
        void shouldRunBodyWhenRestoredMarkerIsFromAnotherKey() {
            markerExists = true
            markerContent = 'fallback-key\n'
            def script = load()
            def ran = false

            script.withCache(path: './archives', key: 'k1') { ran = true }

            assertTrue('body runs when a fallback cache was restored', ran)
            def write = events.find { it[0] == 'writeFile' }
            assertEquals('marker rewritten with the exact key', 'k1', write[2])
        }

        @Test
        void shouldForwardIncludesRestoreKeysNameAndMarker() {
            def script = load()

            script.withCache(
                path: './bin',
                key: 'k1',
                includes: 'tidb-server',
                restoreKeys: ['a', 'b'],
                name: 'my-cache',
                marker: 'done') { }

            def cacheArgs = events.find { it[0] == 'cache' }[1]
            assertEquals('includes forwarded and marker appended', 'tidb-server,done', cacheArgs.includes)
            assertEquals('restoreKeys forwarded', ['a', 'b'], cacheArgs.restoreKeys)
            assertEquals('marker file uses the custom name', './bin/done',
                events.find { it[0] == 'fileExists' }[1])
            assertTrue('name used in log messages',
                events.any { it[0] == 'echo' && it[1].contains('my-cache') })
        }
    }
}
