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
                sh: { Map args ->
                    if (args.returnStdout) {
                        return '/tmp/git-askpass-test'
                    }
                    events << "sh:${args.script}".toString()
                },
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

            assertEquals([
                'resource:scripts/git_askpass.sh',
                'write:/tmp/git-askpass-test:askpass helper',
                'sh:chmod 700 \'/tmp/git-askpass-test\'',
                'credentials:github-bot-https',
                'env:[GIT_ASKPASS=/tmp/git-askpass-test, GIT_TERMINAL_PROMPT=0]',
                'checkout',
                'sh:rm -f \'/tmp/git-askpass-test\'',
            ], events)
        }

        @Test
        void shouldPreserveAskPassCreationFailureWithoutCleanup() {
            def events = []
            def failure = new RuntimeException('temporary directory is unavailable')
            def script = loadProw(
                sh: { Map args ->
                    if (args.returnStdout) {
                        throw failure
                    }
                    events << "sh:${args.script}".toString()
                },
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
}
