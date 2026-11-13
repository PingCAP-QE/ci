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
        // `env` is a Jenkins global; give the script a minimal one unless a test
        // wants specific values. Every real build carries a JOB_NAME, and the
        // checkout refuses to run without one, so the default is a job name whose
        // repository matches `refs()`.
        // A test asks for "this build has no job name" with `JOB_NAME: null`.
        def env = steps.containsKey('env') ? steps['env'] : [:]
        if (!env.containsKey('JOB_NAME')) {
            env = env + [JOB_NAME: 'pingcap/tidb/latest/pull_unit_test']
        }
        steps.each { name, value -> binding.setVariable(name, value) }
        binding.setVariable('env', env)
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
                'git/pingcap/tidb/pr-main-abcdef1-1234567',
                script.getCacheKey('git', pullRefs))
            assertEquals([
                'git/pingcap/tidb/branch-main-',
            ], script.getRestoreKeys('git', pullRefs))
        }

        @Test
        void shouldBuildBranchCacheKeysWithoutPullRequests() {
            def branchRefs = refs() + [pulls: []]

            assertEquals(
                'git/pingcap/tidb/branch-main-abcdef1',
                script.getCacheKey('git', branchRefs))
            assertEquals(
                ['git/pingcap/tidb/branch-main-'],
                script.getRestoreKeys('git', branchRefs))
        }

        @Test
        void shouldOnlyRestorePullRequestCachesFromBranchBuilds() {
            def pullRefs = refs()
            def restoreKeys = script.getRestoreKeys('git', pullRefs)
            def cacheKey = script.getCacheKey('git', pullRefs)

            assertTrue('restore keys must be scoped to branch builds',
                restoreKeys.every { it ==~ /.*\/branch-[^\/]+-$/ })
            assertFalse('a pull request cache must not start with its own restore prefix',
                cacheKey.startsWith(restoreKeys[0]))
        }

        @Test
        void shouldRestoreBranchCachesFromTheSameBranch() {
            def branchRefs = refs() + [pulls: []]

            assertTrue(script.getCacheKey('git', branchRefs)
                .startsWith(script.getRestoreKeys('git', branchRefs)[0]))
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
                    'git config remote.origin.url "https://github.example/pingcap/tidb.git"'))
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

        @Test
        void shouldRefuseAShellInjectionInTheBaseSha() {
            def script = loadProw(
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(
                    refs() + [base_sha: 'abcdef1; rm -rf /'], 7, false, 'https://github.example')
                fail('expected the base sha to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('not a git object name'))
            }
        }

        @Test
        void shouldRefuseARefsRepoThatDiffersFromTheJobFolder() {
            def script = loadProw(
                env: [JOB_NAME: 'pingcap/tidb/latest/ghpr_build'],
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(refs() + [repo: 'tiflow'], 7, false, 'https://github.example')
                fail('expected the mismatched repository to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('is bound to pingcap/tidb'))
            }
        }

        @Test
        void shouldRefuseARefsRepoThatDiffersFromAThreeSegmentAliasJob() {
            // `latest` alias jobs are registered as `<org>/<repo>/<job>`: the
            // repository is still the first two segments.
            def script = loadProw(
                env: [JOB_NAME: 'pingcap/tidb/ghpr_build'],
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(refs() + [repo: 'tiflow'], 7, false, 'https://github.example')
                fail('expected the mismatched repository to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('is bound to pingcap/tidb'))
            }
        }

        @Test
        void shouldAcceptAThreeSegmentAliasJobName() {
            def events = []
            def script = loadProw(
                env: [JOB_NAME: 'pingcap/tidb/ghpr_build'],
                sh: { Map args -> events << args })

            script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')

            assertEquals('one checkout shell call', 1, events.size())
        }

        @Test
        void shouldRefuseAJobNameThatCannotNameARepository() {
            def script = loadProw(
                env: [JOB_NAME: 'ghpr_build'],
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')
                fail('expected a job name with no repository to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('cannot be verified'))
            }
        }

        @Test
        void shouldRefuseWhenTheBuildHasNoJobName() {
            def script = loadProw(
                env: [JOB_NAME: null],
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')
                fail('expected the missing job name to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('JOB_NAME is not set'))
            }
        }

        @Test
        void shouldAcceptARefsRepoThatMatchesTheJobFolderIgnoringCase() {
            def events = []
            def script = loadProw(
                env: [JOB_NAME: 'pingcap-qe/tidb-test/latest/pull_unit_test'],
                sh: { Map args -> events << args })

            script.checkoutPublicRefs(
                refs() + [org: 'PingCAP-QE', repo: 'tidb-test'], 7, false, 'https://github.example')

            assertEquals('one checkout shell call', 1, events.size())
        }

        @Test
        void shouldRefuseANonBranchBaseRef() {
            def script = loadProw(
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(
                    refs() + [base_ref: 'main$(id)'], 7, false, 'https://github.example')
                fail('expected the base ref to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('not a plain branch name'))
            }
        }

        @Test
        void shouldRefuseAnOrgNameThatIsNotPlain() {
            def script = loadProw(
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(
                    refs() + [org: 'pingcap/../evil'], 7, false, 'https://github.example')
                fail('expected the org name to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('not a plain GitHub organization name'))
            }
        }

        @Test
        void shouldRefuseARepoNameThatIsNotPlain() {
            def script = loadProw(
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutPublicRefs(
                    refs() + [repo: 'tidb$(id)'], 7, false, 'https://github.example')
                fail('expected the repo name to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('not a plain GitHub repository name'))
            }
        }
    }

    static class SubmoduleRemotes {
        @Test
        void shouldAllowSubmodulesInsideTheJobsOwnOrganization() {
            def script = loadProw()
            assertEquals([], script.submoduleRemoteViolations("""
                [submodule "extension"]
                    path = pkg/extension/enterprise
                    url = git@github.com:pingcap/tidb-engine-ext.git
            """, 'pingcap', script.defaultSubmoduleSshOrgs()))
        }

        @Test
        void shouldAllowAnyPublicHttpsSubmodule() {
            // tiflash vendors dozens of third-party repositories over https.
            def script = loadProw()
            assertEquals([], script.submoduleRemoteViolations("""
                [submodule "zstd"]
                    url = https://github.com/facebook/zstd.git
                [submodule "grpc"]
                    url = https://github.com/grpc/grpc
                [submodule "cse"]
                    url = git@github.com:tidbcloud/cloud-storage-engine.git
            """, 'pingcap', script.defaultSubmoduleSshOrgs()))
        }

        @Test
        void shouldRefuseAnSshSubmoduleOutsideTheAllowlist() {
            def script = loadProw()
            def violations = script.submoduleRemoteViolations("""
                [submodule "secret"]
                    url = git@github.com:other-org/private-repo.git
            """, 'pingcap', script.defaultSubmoduleSshOrgs())

            assertEquals(1, violations.size())
            assertTrue(violations[0].contains("other-org"))
        }

        @Test
        void shouldRefuseASubmoduleOnAnotherHost() {
            def script = loadProw()
            def violations = script.submoduleRemoteViolations("""
                [submodule "x"]
                    url = git@example.com:pingcap/tidb.git
                [submodule "y"]
                    url = http://github.com/pingcap/tidb.git
            """, 'pingcap', script.defaultSubmoduleSshOrgs())

            assertEquals(2, violations.size())
            assertTrue(violations.every { it.contains('is not a github.com URL') })
        }

        @Test
        void shouldResolveRelativeSubmoduleUrlsInsideTheJobsOrganization() {
            def script = loadProw()
            assertEquals([], script.submoduleRemoteViolations("""
                [submodule "tipb"]
                    url = ../tipb.git
                [submodule "kvproto"]
                    url = ./kvproto.git
            """, 'pingcap', script.defaultSubmoduleSshOrgs()))
        }

        @Test
        void shouldRefuseTheUpdateWithoutFetchingAnything() {
            def events = []
            def script = loadProw(
                env: [JOB_NAME: 'pingcap/tidb/latest/pull_build', GIT_CDN_ENABLED: 'true'],
                readFile: { Map args -> "url = git@github.com:other-org/private-repo.git\n" },
                sshagent: { Map args, Closure body -> body() },
                sh: { Map args -> events << (args.label ?: 'sh') },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'release-8.5',
                                     base_sha: 'abcdef1234567890',
                                     pulls: [[number: 12, sha: '1234567890abcdef']]],
                    'github-bot-ssh', 7, true)
                fail('expected the submodule update to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('refusing to update submodules'))
            }
            assertFalse('no submodule fetch may run',
                events.any { it == 'Update submodules' })
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
                    'git config remote.origin.url "git@github.com:pingcap/tidb.git"'))
            assertTrue(
                events.every { !it.contains('ssh-keyscan') })
        }

        @Test
        void shouldSeedGithubHostKeysFromThePublishedList() {
            def events = []
            def script = loadProw(
                env: [:],
                sshagent: { Map args, Closure body -> body() },
                sh: { Map args -> events << (args.script ?: '').toString() },
            )

            script.checkoutPrivateRefs(refs(), 'github-bot-ssh', 7, false, 'github.com')

            def seed = events.find { it.contains('api.github.com/meta') }
            assertNotNull('host keys must come from the published list', seed)
            assertTrue('every key is recorded for the host', seed.contains("sed 's/^/github.com /'"))
            assertFalse('no trust-on-first-use scan', events.any { it.contains('ssh-keyscan') })
        }

        @Test
        void shouldRefuseANonPlainHostName() {
            def script = loadProw(
                env: [:],
                sshagent: { Map args, Closure body -> body() },
                sh: { Map args -> },
                error: { String message -> throw new IllegalStateException(message) })

            try {
                script.seedSshHostKeys('github.com; rm -rf /')
                fail('expected the host name to be refused')
            } catch (IllegalStateException expected) {
                assertTrue(expected.message.contains('not a plain host name'))
            }
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
                    events << "env:${environment.collect { it.toString() }}".toString()
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
            assertTrue('repository path included in credential prompts',
                env.contains('GIT_CONFIG_KEY_1=credential.useHttpPath'))
            assertTrue('git config count set', env.contains('GIT_CONFIG_COUNT=2'))
            assertEquals('body runs inside scope', 'checkout', events[4])
            assertTrue('helper and cache removed',
                events[5] ==~ /sh:rm -f '\/tmp\/ws\/git-askpass-broker-.*' '\/tmp\/ws\/git-broker-token-.*'/)
        }

        @Test
        void shouldAppendToExistingGitConfigEntries() {
            def events = []
            def script = loadProw(
                env: [WORKSPACE: '/tmp/ws', GIT_BROKER_URL: 'http://broker.example',
                      GIT_CONFIG_COUNT: '3'],
                libraryResource: { String path -> 'broker askpass' },
                writeFile: { Map args -> },
                sh: { Map args -> },
                withEnv: { List environment, Closure body ->
                    events << environment.collect { it.toString() }
                    body()
                },
            )

            script.withGitAskPassFromBroker { }

            def env = events[0]
            assertTrue('count grows instead of resetting',
                env.contains('GIT_CONFIG_COUNT=5'))
            assertTrue('rewrite appended at the first free slot',
                env.contains('GIT_CONFIG_KEY_3=url.https://github.com/.insteadOf'))
            assertTrue('useHttpPath appended at the next slot',
                env.contains('GIT_CONFIG_KEY_4=credential.useHttpPath'))
            assertTrue('existing entries preserved',
                !env.any { it == 'GIT_CONFIG_KEY_0=url.https://github.com/.insteadOf' })
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

    static class CredentialScope {
        private def loadWithEnvCapture(Map env, List envs) {
            return loadProw(
                env: [WORKSPACE: '/tmp/ws', JOB_NAME: 'pingcap/tidb/release-8.5/pull_build'] + env,
                libraryResource: { String path -> 'askpass' },
                writeFile: { Map args -> },
                readFile: { Map args -> '' },
                sh: { Map args -> },
                usernamePassword: { Map args -> 'credentials' },
                withCredentials: { List args, Closure body -> body() },
                withEnv: { List environment, Closure body ->
                    envs << environment.collect { it.toString() }
                    body()
                },
                sshagent: { Map args, Closure body -> body() },
            )
        }

        private static def scopeOf(List envs) {
            return envs.flatten().find { it.toString().startsWith('GIT_CREDENTIAL_ALLOWED_REPOS=') }
        }

        @Test
        void shouldScopeTheHttpCredentialToTheJobRepositoryAndSubmoduleOrgs() {
            def envs = []
            def script = loadWithEnvCapture(
                [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https'], envs)

            script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'release-8.5',
                                 base_sha: 'abcdef1234567890', pulls: []],
                'github-bot-ssh', 7, true)

            final scope = scopeOf(envs)
            assertTrue('the checkout names the repositories its credential may be spent on',
                scope != null)
            assertTrue("this repository: ${scope}", scope.contains('pingcap/tidb'))
            assertTrue("its organization: ${scope}", scope.contains('pingcap/*'))
            assertTrue("the submodule organizations: ${scope}", scope.contains('pingcap-inc/*'))
        }

        @Test
        void shouldScopeTheBrokerCredentialTheSameWay() {
            def envs = []
            def script = loadWithEnvCapture(
                [GIT_BROKER_ENABLED: 'true', GIT_BROKER_URL: 'http://broker.example',
                 GIT_BROKER_ORGS: 'tidbcloud',
                 JOB_NAME: 'tidbcloud/cloud-storage-engine/cloud-engine/pull_integration_test'], envs)

            script.checkoutRefs([org: 'tidbcloud', repo: 'cloud-storage-engine',
                                 base_ref: 'cloud-engine', base_sha: 'abcdef1234567890', pulls: []],
                'github-bot-ssh', 7, false)

            final scope = scopeOf(envs)
            assertTrue('the broker path is scoped too', scope != null)
            assertTrue("this repository: ${scope}", scope.contains('tidbcloud/cloud-storage-engine'))
            assertTrue("the submodule organizations: ${scope}", scope.contains('pingcap-inc/*'))
        }

        @Test
        void shouldHonourTheSubmoduleOrganizationOverrideInTheScope() {
            def envs = []
            def script = loadWithEnvCapture(
                [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https',
                 GIT_SUBMODULE_SSH_ORGS: 'acme, other'], envs)

            script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'release-8.5',
                                 base_sha: 'abcdef1234567890', pulls: []],
                'github-bot-ssh', 7, true)

            final scope = scopeOf(envs)
            assertTrue("the override replaces the default organizations: ${scope}",
                scope.contains('acme/*') && scope.contains('other/*'))
            assertTrue("and the default list is gone: ${scope}", !scope.contains('tidbcloud/*'))
        }

        @Test
        void shouldKeepTheInheritedCredentialForSubmodulesByDefault() {
            def envs = []
            def script = loadWithEnvCapture(
                [GIT_BROKER_ENABLED: 'true', GIT_BROKER_URL: 'http://broker.example',
                 GIT_BROKER_ORGS: 'pingcap-inc'], envs)

            script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'release-8.5',
                                 base_sha: 'abcdef1234567890', pulls: []],
                'github-bot-ssh', 7, true)

            assertTrue('the update keeps the inherited credential unless it is opted in',
                envs.flatten().every { !it.toString().contains('TIPIPELINE_BROKER_URL') })
        }

        @Test
        void shouldIntersectTheBrokerAndTheSubmoduleOrganizations() {
            def script = loadProw(env: [GIT_BROKER_ORGS: 'pingcap-inc, else-org',
                                        GIT_BROKER_REPOS: 'pingcap/tidb-engine-ext, acme/*'])
            assertEquals('only organizations both sides allow',
                ['pingcap-inc/*', 'pingcap/tidb-engine-ext'],
                script.submoduleBrokerScope())
        }

        @Test
        void shouldRefuseToUseTheBrokerWithoutAnOverlap() {
            def script = loadProw(env: [GIT_BROKER_ORGS: 'else-org', GIT_BROKER_URL: 'http://broker.example',
                                        GIT_BROKER_SUBMODULES: 'true'])
            assertFalse('nothing in common, so no broker submodule path',
                script.useBrokerForSubmodules())
        }

        @Test
        void shouldNotNameARepositoryItDoesNotHave() {
            final scope = loadProw().credentialAllowedRepos([:], '')
            assertTrue("only organizations, no repository: ${scope}",
                scope.split(' ').every { it.endsWith('/*') })
        }
    }

    static class BrokerCheckout {
        private def loadWithBroker(Map extraEnv, List events,
                                   String jobName = 'pingcap/tidb/latest/pull_unit_test') {
            return loadProw(
                env: [WORKSPACE: '/tmp/ws', GIT_BROKER_ENABLED: 'true',
                      GIT_BROKER_URL: 'http://broker.example', JOB_NAME: jobName] + extraEnv,
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
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud'], events,
                'tidbcloud/cloud-storage-engine/cloud-engine/pull_integration_test')

            script.checkoutRefs([org: 'tidbcloud', repo: 'cloud-storage-engine',
                                 base_ref: 'cloud-engine', base_sha: 'abcdef1234567890',
                                 pulls: []], 'github-bot-ssh', 7)

            assertTrue('no ssh agent on the broker path', events.every { !it.startsWith('sshagent:') })
            assertTrue('https remote used',
                events.last().contains(
                    'git config remote.origin.url "https://github.com/tidbcloud/cloud-storage-engine.git"'))
        }

        @Test
        void shouldKeepSshUrlForCdnInstances() {
            def events = []
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud', GIT_CDN_ENABLED: 'true'], events,
                'tidbcloud/cloud-storage-engine/cloud-engine/pull_integration_test')

            script.checkoutRefs([org: 'tidbcloud', repo: 'cloud-storage-engine',
                                 base_ref: 'cloud-engine', base_sha: 'abcdef1234567890',
                                 pulls: []], 'github-bot-ssh', 7)

            assertTrue('cdn rewrite stays available',
                events.last().contains(
                    'git config remote.origin.url "git@github.com:tidbcloud/cloud-storage-engine.git"'))
        }

        @Test
        void shouldKeepLegacyCredentialsForOtherOrgs() {
            def events = []
            def script = loadWithBroker([GIT_BROKER_ORGS: 'tidbcloud'], events)

            script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'main',
                                 base_sha: 'abcdef1234567890', pulls: []],
                'github-bot-ssh', 7)

            assertTrue('ssh agent still used for non-allowlisted orgs',
                events.any { it == 'sshagent:[github-bot-ssh]' })
            assertTrue('ssh remote kept',
                events.last().contains('git config remote.origin.url "git@github.com:pingcap/tidb.git"'))
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

        @Test
        void shouldReportBrokerEnablementPerRepo() {
            def script = loadProw(env: [
                GIT_BROKER_ENABLED: 'true',
                GIT_BROKER_REPOS: ' pingcap-inc/enterprise-plugin , PingCAP-INC/Tiflow ',
            ])

            // Repository allowlist narrows the migration without opening the org.
            assertTrue(script.useBrokerCredentials([org: 'pingcap-inc', repo: 'enterprise-plugin']))
            assertTrue(script.useBrokerCredentials([org: 'pingcap-inc', repo: 'tiflow']))
            assertFalse(script.useBrokerCredentials([org: 'pingcap-inc', repo: 'tidb']))
            assertFalse(script.useBrokerCredentials([org: 'pingcap', repo: 'enterprise-plugin']))

            def wildcard = loadProw(env: [
                GIT_BROKER_ENABLED: 'true',
                GIT_BROKER_REPOS: 'pingcap-inc/*',
            ])
            assertTrue(wildcard.useBrokerCredentials([org: 'pingcap-inc', repo: 'anything']))
            assertFalse(wildcard.useBrokerCredentials([org: 'pingcap', repo: 'anything']))
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

    // ============================================================
    // Git askpass credential host policy
    // ============================================================
    // The helper is a shell script (a library resource), so these cases execute
    // the real script with the prompts git actually passes. Both allowed hosts
    // must keep their credentials; every other host must get nothing.
    static class AskPassCredentialHostPolicy {
        private static final File ASKPASS_SCRIPT =
            new File('libraries/tipipeline/resources/scripts/git_askpass.sh').absoluteFile

        private static Map run(String prompt) {
            def builder = new ProcessBuilder(['sh', ASKPASS_SCRIPT.path, prompt])
            builder.environment().put('TIPIPELINE_GIT_USERNAME', 'bot-user')
            builder.environment().put('TIPIPELINE_GIT_PASSWORD', 'bot-password')
            def process = builder.start()
            def out = process.inputStream.text
            def err = process.errorStream.text
            int rc = process.waitFor()
            return [rc: rc, out: out, err: err]
        }

        @Test
        void shouldAnswerCredentialPromptsOnlyForAllowlistedHosts() {
            // Table: prompt exactly as git formats it, expected exit status and
            // expected stdout (empty means the credential must not be printed).
            def cases = [
                [prompt: "Username for 'https://github.com': ",
                 rc: 0, out: 'bot-user\n'],
                [prompt: "Password for 'https://x-access-token@github.com/org/repo.git': ",
                 rc: 0, out: 'bot-password\n'],
                [prompt: "Username for 'http://git-cdn.cache.svc:8000': ",
                 rc: 0, out: 'bot-user\n'],
                [prompt: "Password for 'http://x-access-token@git-cdn.cache.svc:8000/org/repo.git': ",
                 rc: 0, out: 'bot-password\n'],
                [prompt: "Password for 'ssh://git@github.com/org/repo.git': ",
                 rc: 0, out: 'bot-password\n'],
                [prompt: "Password for 'git@github.com:org/repo.git': ",
                 rc: 0, out: 'bot-password\n'],
                [prompt: "Password for 'https://GitHub.com/org/repo.git': ",
                 rc: 0, out: 'bot-password\n'],
                // A PR-controlled .gitmodules can point a submodule at any of
                // these hosts; none of them may receive the bot credential.
                [prompt: "Password for 'https://evil.example/org/repo.git': ",
                 rc: 1, out: ''],
                [prompt: "Username for 'https://github.com.evil.example': ",
                 rc: 1, out: ''],
                [prompt: "Password for 'https://127.0.0.1:18099/org/repo.git': ",
                 rc: 1, out: ''],
                [prompt: "Password for 'ssh://git@evil.example/org/repo.git': ",
                 rc: 1, out: ''],
                [prompt: "Password for 'git@evil.example:org/repo.git': ",
                 rc: 1, out: ''],
                // Not a credential prompt at all.
                [prompt: "git@github.com's password: ",
                 rc: 1, out: ''],
            ]

            cases.each { testCase ->
                def actual = run(testCase.prompt)
                assertEquals("exit status for prompt [${testCase.prompt}]",
                    testCase.rc, actual.rc)
                assertEquals("stdout for prompt [${testCase.prompt}]",
                    testCase.out, actual.out)
            }
        }

        @Test
        void shouldNotExposeCredentialEnvironmentForRejectedHosts() {
            def actual = run("Password for 'https://evil.example/org/repo.git': ")

            assertFalse('rejected host must not receive the password',
                actual.out.contains('bot-password'))
            assertTrue('rejection should be explained on stderr',
                actual.err.contains('evil.example'))
        }
    }

}
