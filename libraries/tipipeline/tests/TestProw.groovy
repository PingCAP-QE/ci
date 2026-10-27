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

        @Test
        void shouldProvideBrokerAskPassAsLibraryResource() {
            assertTrue(
                'Token broker askpass helper must be under the Jenkins library resources directory',
                new File('libraries/tipipeline/resources/scripts/git_askpass_broker.sh').isFile())
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
                'checkout must resolve the real git refs path for submodule/worktree checkouts',
                checkoutScript.contains('git rev-parse --git-path refs'))
            assertTrue(
                'checkout must leave a file under the resolved refs directory so it survives stash/unstash',
                checkoutScript.contains('mkdir -p "$refs_dir"'))
            assertTrue(
                'checkout must leave a file under the resolved refs directory so it survives stash/unstash',
                checkoutScript.contains('touch "$refs_dir/.keep"'))
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

    static class AskPassBroker {
        @Test
        void shouldScopeBrokerEnvironmentAndCleanUp() {
            def events = []
            def script = loadProw(
                env: [WORKSPACE: '/tmp/ws', GIT_BROKER_URL: 'http://broker.example'],
                libraryResource: { String path ->
                    events << "resource:${path}".toString()
                    'broker askpass'
                },
                writeFile: { Map args -> events << "write:${args.file}".toString() },
                sh: { Map args -> events << "sh:${args.script}".toString() },
                withEnv: { List environment, Closure body ->
                    events << "env:${environment}".toString()
                    body()
                },
            )

            script.withGitAskPassFromBroker([repo: 'tidbcloud/cloud-storage-engine']) {
                events << 'checkout'
            }

            assertEquals('resource loaded', 'resource:scripts/git_askpass_broker.sh', events[0])
            assertTrue('helper written from resource',
                events[1] ==~ /write:\/tmp\/ws\/git-askpass-broker-.*/)
            assertTrue('helper made executable',
                events[2] ==~ /sh:chmod 700 '\/tmp\/ws\/git-askpass-broker-.*'/)
            def env = events[3]
            assertTrue('askpass scoped', env.contains('GIT_ASKPASS=/tmp/ws/git-askpass-broker-'))
            assertTrue('terminal prompts disabled', env.contains('GIT_TERMINAL_PROMPT=0'))
            assertTrue('broker url passed', env.contains('TIPIPELINE_BROKER_URL=http://broker.example'))
            assertTrue('repo pinned', env.contains('TIPIPELINE_BROKER_REPO=tidbcloud/cloud-storage-engine'))
            assertTrue('default permissions',
                env.contains('TIPIPELINE_BROKER_PERMISSIONS={"contents":"read"}'))
            assertTrue('token cache scoped', env.contains('TIPIPELINE_BROKER_CACHE=/tmp/ws/git-broker-token-'))
            assertTrue('ssh urls rewritten',
                env.contains('GIT_CONFIG_KEY_0=url.https://github.com/.insteadOf'))
            assertEquals('body runs inside scope', 'checkout', events[4])
            assertTrue('helper and cache removed',
                events[5] ==~ /sh:rm -f '\/tmp\/ws\/git-askpass-broker-.*' '\/tmp\/ws\/git-broker-token-.*'/)
        }

        @Test
        void shouldRequireBrokerUrl() {
            def script = loadProw(env: [WORKSPACE: '/tmp/ws'])

            try {
                script.withGitAskPassFromBroker { fail('body must not run') }
                fail('missing broker url should fail')
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.message.contains('GIT_BROKER_URL'))
            }
        }
    }

    static class BrokerCheckout {
        private def loadWithBroker(Map extraEnv, List events) {
            return loadProw(
                env: [WORKSPACE: '/tmp/ws', GIT_BROKER_ENABLED: 'true',
                      GIT_BROKER_URL: 'http://broker.example'] + extraEnv,
                libraryResource: { String path -> 'broker askpass' },
                writeFile: { Map args -> },
                sh: { Map args ->
                    def script = (args.script ?: '').toString()
                    if (script.contains('git config remote.origin.url')) {
                        events << script
                    }
                },
                withEnv: { List environment, Closure body -> body() },
                sshagent: { Map args, Closure body ->
                    events << "sshagent:${args.credentials}".toString()
                    body()
                },
            )
        }

        @Test
        void shouldUseHttpsAndBrokerForAllowlistedOrgWithoutCdn() {
            def events = []
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud'], events)

            script.checkoutRefs([org: 'tidbcloud', repo: 'cloud-storage-engine',
                                 base_ref: 'cloud-engine', pulls: []], 'github-bot-ssh', 7)

            assertTrue('no ssh agent on the broker path', events.every { !it.startsWith('sshagent:') })
            assertTrue('https remote used',
                events.last().contains(
                    'git config remote.origin.url https://github.com/tidbcloud/cloud-storage-engine.git'))
        }

        @Test
        void shouldKeepSshUrlForCdnInstances() {
            def events = []
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud', GIT_CDN_ENABLED: 'true'], events)

            script.checkoutRefs([org: 'tidbcloud', repo: 'cloud-storage-engine',
                                 base_ref: 'cloud-engine', pulls: []], 'github-bot-ssh', 7)

            assertTrue('cdn rewrite stays available',
                events.last().contains(
                    'git config remote.origin.url git@github.com:tidbcloud/cloud-storage-engine.git'))
        }

        @Test
        void shouldKeepLegacyCredentialsForOtherOrgs() {
            def events = []
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud'], events)

            script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'main', pulls: []],
                'github-bot-ssh', 7)

            assertTrue('ssh agent still used for non-allowlisted orgs',
                events.any { it == 'sshagent:[github-bot-ssh]' })
            assertTrue('ssh remote kept',
                events.last().contains('git config remote.origin.url git@github.com:pingcap/tidb.git'))
        }

        @Test
        void shouldReportBrokerEnablementPerOrg() {
            def script = loadProw(env: [GIT_BROKER_ENABLED: 'true', GIT_BROKER_ORGS: ' tidbcloud , PingCAP-INC '])

            assertTrue(script.useBrokerCredentials([org: 'tidbcloud', repo: 'x']))
            assertTrue(script.useBrokerCredentials([org: 'PingCAP-INC', repo: 'x']))
            assertFalse(script.useBrokerCredentials([org: 'pingcap', repo: 'x']))
            assertFalse(script.useBrokerCredentials([org: '', repo: 'x']))
            assertFalse(script.useBrokerCredentials(null))

            def disabled = loadProw(env: [GIT_BROKER_ENABLED: 'false'])
            assertFalse(disabled.useBrokerCredentials([org: 'tidbcloud', repo: 'x']))
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
