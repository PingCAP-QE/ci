import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Table-driven unit tests for component.groovy utility functions.
 *
 * Usage:
 *   groovy libraries/tipipeline/tests/TestComponent.groovy
 */
@RunWith(Enclosed.class)
class TestComponent {
    private static def loadScript() {
        new GroovyShell().parse(
            new File("libraries/tipipeline/vars/component.groovy"))
    }

    private static def loadScriptWithBindings(Map steps = [:]) {
        def binding = new Binding()
        steps.each { name, value -> binding.setVariable(name, value) }
        new GroovyShell(binding).parse(
            new File("libraries/tipipeline/vars/component.groovy"))
    }

    // ============================================================
    // parseCIParamsFromPRTitle
    // ============================================================
    static class ParseCIParams {
        private def parseCIParams

        @Before
        void setUp() {
            def script = loadScript()
            parseCIParams = { String title ->
                script.invokeMethod('parseCIParamsFromPRTitle', [title])
            }
        }

        @Test
        void shouldExtractParamsFromTitle() {
            def cases = [
                // --- Basic key=val extraction ---
                [title: 'feat: support fast read | tidb=pr/123',
                 expected: [tidb: 'pr/123']],
                [title: 'feat: support fast read | tidb=pr/123 pd=@v8.5.0',
                 expected: [tidb: 'pr/123', pd: '@v8.5.0']],
                [title: 'feat: support fast read (#12467)| tidb=pr/123',
                 expected: [tidb: 'pr/123']],
                [title: 'feat: support fast read (#12467) | tidb=pr/123 pd=@v8.5.0',
                 expected: [tidb: 'pr/123', pd: '@v8.5.0']],

                // Multiple pipes — last segment wins
                [title: 'feat: support fast read | tidb=pr/123 (#12456) | tidb=pr/456 pd=@v8.5.0',
                 expected: [tidb: 'pr/456', pd: '@v8.5.0']],

                // --- Value format variants ---
                [title: 'feat: support fast read | pd=@v8.5.0',
                 expected: [pd: '@v8.5.0']],
                [title: 'feat: support fast read | tidb=v8.5.0',
                 expected: [tidb: 'v8.5.0']],
                [title: 'feat: support fast read | my-component=test',
                 expected: ['my-component': 'test']],
                [title: 'feat: support fast read | tidb = pr/123',
                 expected: [tidb: 'pr/123']],
            ]

            cases.each { c ->
                def result = parseCIParams(c.title)
                assertEquals(c.title, c.expected, result)
            }
        }

        @Test
        void shouldReturnEmptyWhenNoValidParams() {
            def cases = [
                // No pipe at all
                [title: 'feat: support fast read',            desc: 'no pipe'],
                // Only PR number, no pipe
                [title: 'feat: support fast read (#12467)',   desc: 'only PR#'],
                // Whitespace only after pipe
                [title: 'feat: support fast read |  ',        desc: 'empty after pipe'],
                // Cherry-pick: (#N) suffix after pipe
                [title: 'feat: support fast read | tidb=pr/123 pd=@v8.5.0 (#1235)', desc: 'cherry-pick suffix'],
                [title: 'feat: support fast read | tidb=pr/123 (#12456)',           desc: 'cherry-pick single param'],
            ]

            cases.each { c ->
                def result = parseCIParams(c.title)
                assertTrue("${c.desc}: ${c.title} → should be empty", result.isEmpty())
            }
        }
    }

    // ============================================================
    // validatePreBuiltComponentParams
    // ============================================================
    static class ValidatePreBuiltComponentParams {
        private def validate

        @Before
        void setUp() {
            def script = loadScript()
            validate = { String title, String targetBranch ->
                script.invokeMethod('validatePreBuiltComponentParams', [title, targetBranch])
            }
        }

        @Test
        void shouldPassForSupportedComponentsOnNonCoreBranches() {
            def cases = [
                [title: 'feat: x | pd=@v8.5.0',           branch: 'feature/my-feature',   desc: 'pd on feature branch'],
                [title: 'feat: x | tidb=@v1.0.0',         branch: 'hotfix/fix-123',       desc: 'tidb on hotfix branch'],
                [title: 'feat: x | tikv=@abc123 pd=@v2',  branch: 'dev-branch',           desc: 'multiple on dev branch'],
                [title: 'feat: x | ticdc=@latest',        branch: 'feature-x',            desc: 'ticdc on feature'],
            ]

            cases.each { c ->
                def errors = validate(c.title, c.branch)
                assertTrue("${c.desc}: ${c.title} on ${c.branch} → should pass", errors.isEmpty())
            }
        }

        @Test
        void shouldRejectUnsupportedComponents() {
            def cases = [
                [title: 'feat: x | tso=@v1.0',           component: 'tso'],
                [title: 'feat: x | cdc=@v1.0',           component: 'cdc'],
                [title: 'feat: x | pd=@v1 tikv=@v2 br=@v3', component: 'br'],
            ]

            cases.each { c ->
                def errors = validate(c.title, 'feature/test')
                assertFalse("should reject ${c.component}", errors.isEmpty())
                assertTrue("error should mention ${c.component}",
                    errors.any { it.contains(c.component) })
            }
        }

        @Test
        void shouldRejectPreBuiltOnCoreBranches() {
            def cases = [
                [branch: 'master',                  desc: 'master'],
                [branch: 'main',                    desc: 'main'],
                [branch: 'release-8.5',             desc: 'release-X.Y'],
                [branch: 'release-9.0-beta.1',      desc: 'release-X.Y-beta.N'],
                [branch: 'release-8.5-beta.2',      desc: 'release-X.Y-beta.N (higher)'],
                [branch: 'release-nextgen-20250601', desc: 'release-nextgen-YYYYMMDD'],
                [branch: 'release-nextgen-26.3.0-20260817', desc: 'release-nextgen-X.Y.Z-YYYYMMDD'],
            ]

            cases.each { c ->
                def errors = validate('feat: x | pd=@v1.0', c.branch)
                assertFalse("${c.desc} should reject pre-built", errors.isEmpty())
                assertTrue("error should mention branch restriction",
                    errors.any { it.contains('not allowed on branch') })
            }
        }

        @Test
        void shouldPassWhenNoPreBuiltParams() {
            def cases = [
                [title: 'feat: x | tidb=pr/123',         branch: 'master'],
                [title: 'feat: x | pd=release-8.5',      branch: 'release-8.5'],
                [title: 'feat: no params at all',         branch: 'master'],
            ]

            cases.each { c ->
                def errors = validate(c.title, c.branch)
                assertTrue("${c.title} on ${c.branch} → should pass", errors.isEmpty())
            }
        }
    }

    // ============================================================
    // computeBranchFromPR
    // ============================================================
    static class ComputeBranchFromPR {
        private def script

        @Before
        void setUp() {
            script = loadScript()
        }

        private String branch(String component, String targetBranch,
                              String title, String trunk = 'master') {
            script.invokeMethod('computeBranchFromPR',
                [component, targetBranch, title, trunk])
        }

        private String captureLog(Closure body) {
            def out = new ByteArrayOutputStream()
            def original = System.out
            System.setOut(new PrintStream(out))
            try {
                body()
            } finally {
                System.setOut(original)
            }
            return out.toString()
        }

        @Test
        void shouldLogPrTitleParamResolution() {
            def log = captureLog { branch('tidb', 'master', 'feat: support fast read | tidb=pr/123') }
            assertTrue("expected PR-title log in: ${log}", log.contains("from PR title param 'tidb=pr/123'"))
            assertTrue("expected resolved branch in: ${log}", log.contains("-> 'pr/123'"))
        }

        @Test
        void shouldLogDerivedBranchResolution() {
            def releaseLog = captureLog { branch('tikv', 'release-8.5', 'feat: support fast read') }
            assertTrue("expected release-rule log in: ${releaseLog}", releaseLog.contains('release branch rule'))
            assertTrue("expected resolved branch in: ${releaseLog}", releaseLog.contains("-> 'release-8.5'"))

            def trunkLog = captureLog { branch('tikv', 'feature/my-feature', 'feat: support fast read') }
            assertTrue("expected generic-feature log in: ${trunkLog}", trunkLog.contains('generic feature branch rule'))
            assertTrue("expected trunk fallback in: ${trunkLog}", trunkLog.contains("-> 'master'"))

            def keepLog = captureLog { branch('tikv', 'master', 'feat: support fast read') }
            assertTrue("expected keep-target log in: ${keepLog}", keepLog.contains('keep the target branch'))
            assertTrue("expected target branch in: ${keepLog}", keepLog.contains("-> 'master'"))
        }

        @Test
        void shouldUseParamFromTitle() {
            def cases = [
                // component, title,                         target,     expected
                ['tidb',     'feat: support fast read | tidb=pr/123',                   'master',       'pr/123'],
                ['tidb',     'feat: support fast read | tidb=release-8.5',              'master',       'release-8.5'],
                ['tidb',     "feat: support fast read | tidb=${'a'*40}",                'master',       'a' * 40],
                ['tidb',     'feat: support fast read | tidb=pr/100 (#12345) | tidb=pr/200 pd=pr/300',
                                                                                        'release-8.5',  'pr/200'],
            ]

            cases.each { c ->
                def (component, title, target, expected) = c
                def result = branch(component, target, title)
                assertEquals("${component}=... on ${target}: ${title}", expected, result)
            }
        }

        @Test
        void shouldIgnoreParamOnCherryPickTitle() {
            def cases = [
                // component, title,                                          target, expected
                ['tidb',     'feat: support fast read | tidb=release-8.5 (#12345)', 'master',      'master'],
                ['tidb',     'feat: support fast read | tidb=pr/100 (#12345)',      'release-8.5', 'release-8.5'],
            ]

            cases.each { c ->
                def (component, title, target, expected) = c
                def result = branch(component, target, title)
                assertEquals("cherry-pick: ${title}", expected, result)
            }
        }

        @Test
        void shouldDeriveBranchFromTarget() {
            def cases = [
                // component, title (no param), target branch,              expected
                ['tidb',     'feat: support fast read', 'master',                        'master'],
                ['tidb',     'feat: support fast read', 'release-8.5',                   'release-8.5'],
                ['tidb',     'feat: support fast read', 'release-8.5-beta.1',            'release-8.5-beta.1'],
                ['tidb',     'feat: support fast read', 'release-6.2-20220801',          'release-6.2'],
                ['tidb',     'feat: support fast read', 'release-nextgen-202603',        'release-nextgen-202603'],
                ['tidb',     'feat: support fast read', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
                ['tidb',     'feat: support fast read', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['tidb',     'feat: support fast read', 'feature/my-feature',            'master'],
            ]

            cases.each { c ->
                def (component, title, target, expected) = c
                def result = branch(component, target, title)
                assertEquals("branch derivation: ${target} → ${expected}", expected, result)
            }
        }

        @Test
        void shouldDerivePatchVersionForPatchAwareComponents() {
            def cases = [
                // component,    target branch (hotfix-v8.5.1),           expected
                ['tidb-test',    'release-8.5-20230101-v8.5.1',           'release-8.5.1'],
                ['plugin',       'release-8.5-20230101-v8.5.1',           'release-8.5.1'],
                ['tidb',         'release-8.5-20230101-v8.5.1',           'release-8.5'],
                ['tikv',         'release-8.5-20230101-v8.5.1',           'release-8.5'],
            ]

            cases.each { c ->
                def (component, target, expected) = c
                def result = branch(component, target, 'feat: support fast read')
                assertEquals("${component} on ${target}", expected, result)
            }
        }
    }

    // ============================================================
    // computeNextgenPeerBranch
    // ============================================================
    static class ComputeNextgenPeerBranch {
        private def script

        @Before
        void setUp() {
            script = loadScript()
        }

        private String peerBranch(String branch) {
            script.invokeMethod('computeNextgenPeerBranch', [branch])
        }

        @Test
        void shouldMapNextgenPatchBranches() {
            def cases = [
                // branch,                             expected
                ['release-nextgen-25.10-20251123',     'release-nextgen-20251011'],
                ['release-nextgen-26.3.9-20260817',    'release-nextgen-202603'],
                ['release-nextgen-26.10.1-20261005',   'release-nextgen-202610'],
                ['release-nextgen-27.1.0-20270101',    'release-nextgen-202701'],
            ]

            cases.each { c ->
                def (branch, expected) = c
                def result = peerBranch(branch)
                assertEquals("peer branch: ${branch} → ${expected}", expected, result)
            }
        }

        @Test
        void shouldKeepOtherBranchesUnchanged() {
            def cases = [
                'release-nextgen-202603',
                'release-nextgen-20251011',
                'release-nextgen-20260301',
                'master',
                'release-8.5',
            ]

            cases.each { branch ->
                assertEquals("unchanged: ${branch}", branch, peerBranch(branch))
            }
        }
    }

    // ============================================================
    // Git CDN aware component checkouts (git-cdn askpass)
    // ============================================================
    static class GitCdnCheckout {
        @Test
        void shouldInstallHttpAskPassWhenCdnEnabled() {
            def events = []
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https', WORKSPACE: '/ws'],
                libraryResource: { String path ->
                    events << "resource:${path}".toString()
                    'askpass helper'
                },
                writeFile: { Map args -> events << "write:${args.file}".toString() },
                sh: { Map args -> events << "sh:${args.label}".toString() },
                usernamePassword: { Map args -> args },
                withCredentials: { List creds, Closure body ->
                    events << "credentials:${creds[0].credentialsId}".toString()
                    body()
                },
                withEnv: { List environment, Closure body ->
                    events << "env:${environment}".toString()
                    body()
                },
            ])

            script.withGitCdnAskPass { events << 'body' }

            assertTrue(events.contains('resource:scripts/git_askpass.sh'))
            assertTrue(events.contains('sh:Prepare Git HTTP credential helper'))
            assertTrue(events.contains('credentials:github-bot-https'))
            assertTrue('body must run while GIT_ASKPASS is active',
                events.indexOf('credentials:github-bot-https') < events.indexOf('body'))
            assertTrue('askpass script must be cleaned up',
                events.contains('sh:Remove Git HTTP credential helper'))
        }

        @Test
        void shouldSkipAskPassWhenCdnDisabled() {
            def events = []
            def script = loadScriptWithBindings([
                env: [:],
                writeFile: { Map args -> events << 'write' },
                sh: { Map args -> events << 'sh' },
                withCredentials: { List creds, Closure body -> events << 'credentials'; body() },
                withEnv: { List environment, Closure body -> events << 'env'; body() },
            ])

            script.withGitCdnAskPass { events << 'body' }

            assertEquals(['body'], events)
        }

        @Test
        void shouldSkipAskPassWhenCdnEnabledWithoutHttpCredential() {
            def events = []
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true', WORKSPACE: '/ws'],
            ])

            script.withGitCdnAskPass { events << 'body' }

            assertEquals(['body'], events)
        }

        @Test
        void shouldRewriteGithubUrlAndUseHttpCredentialWhenCdnEnabled() {
            def scmArg = null
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https'],
                checkout: { Map args -> scmArg = args },
            ])

            script.checkoutSingle('git@github.com:PingCAP-QE/tidb-test.git', 'master', 'master', 'github-sre-bot-ssh')

            assertNotNull(scmArg)
            def remote = scmArg.scm.userRemoteConfigs[0]
            assertEquals('http://git-cdn.cache.svc:8000/PingCAP-QE/tidb-test.git', remote.url)
            assertEquals('github-bot-https', remote.credentialsId)
        }

        @Test
        void shouldRewriteGithubHttpsUrlWhenCdnEnabled() {
            def scmArg = null
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https'],
                checkout: { Map args -> scmArg = args },
            ])

            script.checkoutSingle('https://github.com/PingCAP-QE/tidb-test.git', 'master', 'master', 'github-sre-bot-ssh')

            assertNotNull(scmArg)
            def remote = scmArg.scm.userRemoteConfigs[0]
            assertEquals('http://git-cdn.cache.svc:8000/PingCAP-QE/tidb-test.git', remote.url)
            assertEquals('github-bot-https', remote.credentialsId)
        }

        @Test
        void shouldRewriteUrlWithoutCredentialWhenNoHttpCredentialConfigured() {
            def scmArg = null
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true'],
                checkout: { Map args -> scmArg = args },
            ])

            script.checkoutSingle('git@github.com:PingCAP-QE/tidb-test.git', 'master', 'master', 'github-sre-bot-ssh')

            assertNotNull(scmArg)
            def remote = scmArg.scm.userRemoteConfigs[0]
            assertEquals('http://git-cdn.cache.svc:8000/PingCAP-QE/tidb-test.git', remote.url)
            assertEquals('', remote.credentialsId)
        }

        @Test
        void shouldKeepSshUrlAndCredentialWhenCdnDisabled() {
            def scmArg = null
            def script = loadScriptWithBindings([
                env: [:],
                checkout: { Map args -> scmArg = args },
            ])

            script.checkoutSingle('git@github.com:PingCAP-QE/tidb-test.git', 'master', 'master', 'github-sre-bot-ssh')

            assertNotNull(scmArg)
            def remote = scmArg.scm.userRemoteConfigs[0]
            assertEquals('git@github.com:PingCAP-QE/tidb-test.git', remote.url)
            assertEquals('github-sre-bot-ssh', remote.credentialsId)
        }

        @Test
        void shouldNotRewriteNonGithubUrlEvenWhenCdnEnabled() {
            def scmArg = null
            def script = loadScriptWithBindings([
                env: [GIT_CDN_ENABLED: 'true', GIT_HTTP_CREDENTIALS_ID: 'github-bot-https'],
                checkout: { Map args -> scmArg = args },
            ])

            script.checkoutSingle('git@gitlab.example.com:foo/bar.git', 'master', 'master', 'some-ssh-cred')

            assertNotNull(scmArg)
            def remote = scmArg.scm.userRemoteConfigs[0]
            assertEquals('git@gitlab.example.com:foo/bar.git', remote.url)
            assertEquals('some-ssh-cred', remote.credentialsId)
        }
    }

    // ============================================================
    // component-branch-mapping.yaml driven special mappings
    // ============================================================
    static class ComponentBranchMapping {
        private static final String RESOURCE = 'configs/component-branch-mapping.yaml'

        private def scriptWithConfig(Map config) {
            return loadScriptWithBindings([
                libraryResource: { String path -> 'mappings: []' },
                readYaml: { Map args -> config },
            ])
        }

        private String branch(def script, String component, String target,
                              String title = 'feat: support fast read', String trunk = 'master') {
            script.invokeMethod('computeBranchFromPR', [component, target, title, trunk])
        }

        @Test
        void shouldRequestConfigFromConfigsSubdir() {
            def requested = []
            def script = loadScriptWithBindings([
                libraryResource: { String path -> requested << path; 'mappings: []' },
                readYaml: { Map args -> [mappings: []] },
            ])
            script.invokeMethod('computeBranchFromPR', ['tidb', 'feature/my-feature', 'feat: x', 'master'])
            assertEquals([RESOURCE], requested)
        }

        @Test
        void shouldLogWhenSpecialMappingIsApplied() {
            def script = scriptWithConfig([
                mappings: [[match: 'feature/release-8.5-fts', default: '$release',
                            components: [pd: '$self']]],
            ])
            def out = new ByteArrayOutputStream()
            def original = System.out
            System.setOut(new PrintStream(out))
            try {
                branch(script, 'pd', 'feature/release-8.5-fts')
            } finally {
                System.setOut(original)
            }
            def log = out.toString()
            assertTrue("log should mention the config resource: ${log}",
                log.contains('configs/component-branch-mapping.yaml'))
            assertTrue("log should mention the matched rule: ${log}",
                log.contains("match='feature/release-8.5-fts'"))
            assertTrue("log should mention the value source: ${log}", log.contains('components.pd'))
            assertTrue("log should mention the resolved branch: ${log}",
                log.contains("-> 'feature/release-8.5-fts'"))
        }

        @Test
        void shouldApplyDefaultAndComponentOverride() {
            def script = scriptWithConfig([
                mappings: [
                    [match: 'feature/release-8.5-fts', default: '$release',
                     components: [pd: '$self', tikv: 'release-8.5-20260101-v8.5.9']],
                ],
            ])
            def cases = [
                // component, expected
                ['tidb', 'release-8.5'],
                ['pd',   'feature/release-8.5-fts'],
                ['tikv', 'release-8.5-20260101-v8.5.9'],
            ]
            cases.each { c ->
                def (component, expected) = c
                assertEquals("${component} on feature/release-8.5-fts", expected,
                    branch(script, component, 'feature/release-8.5-fts'))
            }
        }

        @Test
        void shouldSupportReleaseAndPatchTokens() {
            def script = scriptWithConfig([
                mappings: [
                    [match: 'feature/release-8.5.5-active-active', default: '$release',
                     components: [tidb: '$self', 'tidb-test': 'release-8.5-20260121-v8.5.5', plugin: '$patch']],
                ],
            ])
            def cases = [
                ['tidb',      'feature/release-8.5.5-active-active'],
                ['tidb-test', 'release-8.5-20260121-v8.5.5'],
                ['plugin',    'release-8.5.5'],
                ['tikv',      'release-8.5'],
            ]
            cases.each { c ->
                def (component, expected) = c
                assertEquals("${component} on feature/release-8.5.5-active-active", expected,
                    branch(script, component, 'feature/release-8.5.5-active-active'))
            }
        }

        @Test
        void shouldMatchByRegex() {
            def script = scriptWithConfig([
                mappings: [[matchRegex: '^feature/release-8\\.5-fts.*$', default: '$self']],
            ])
            assertEquals('feature/release-8.5-fts', branch(script, 'tikv', 'feature/release-8.5-fts'))
            assertEquals('feature/release-8.5-fts-abc', branch(script, 'tikv', 'feature/release-8.5-fts-abc'))
        }

        @Test
        void shouldFallBackToGenericDerivationWhenNoMappingMatches() {
            def script = scriptWithConfig([
                mappings: [[match: 'feature/release-8.5-fts', default: '$release']],
            ])
            assertEquals('master', branch(script, 'tidb', 'feature/my-feature'))
            assertEquals('release-8.5', branch(script, 'tikv', 'feature/release-8.5-other'))
        }

        @Test
        void shouldPreferPrTitleParamOverMapping() {
            def script = scriptWithConfig([
                mappings: [[match: 'feature/release-8.5-fts', default: '$release']],
            ])
            assertEquals('pr/123', branch(script, 'pd', 'feature/release-8.5-fts', 'feat: x | pd=pr/123'))
        }

        @Test
        void shouldDegradeGracefullyWithoutConfigResource() {
            // No libraryResource/readYaml bindings: loader must fall back to the
            // generic derivation instead of throwing.
            def script = loadScript()
            assertEquals('release-8.5', branch(script, 'tidb', 'feature/release-8.5-fts'))
        }

        @Test
        void shouldApplyShippedConfigBehavior() {
            def realConfig = new groovy.yaml.YamlSlurper()
                .parse(new File('libraries/tipipeline/resources/configs/component-branch-mapping.yaml'))
            def script = scriptWithConfig(realConfig)

            def cases = [
                // component,   target branch,                          expected
                ['tidb',        'feature/release-8.5-materialized-view', 'feature/release-8.5-materialized-view'],
                ['ticdc',       'feature/release-8.5-materialized-view', 'release-8.5'],
                ['tidb',        'feature/release-8.5.5-active-active',   'feature/release-8.5.5-active-active'],
                ['ticdc',       'feature/release-8.5.5-active-active',   'feature/release-8.5.5-active-active'],
                ['tidb-test',   'feature/release-8.5.5-active-active',   'release-8.5-20260121-v8.5.5'],
                ['plugin',      'feature/release-8.5.5-active-active',   'release-8.5.5'],
                ['tikv',        'feature/release-8.5.5-active-active',   'release-8.5'],
                ['tidb',        'feature/release-8.5-fts',               'feature/release-8.5-fts'],
                ['pd',          'feature/release-8.5-fts',               'feature/release-8.5-fts'],
                ['tici',        'feature/release-8.5-fts',               'release-fts-202602'],
            ]
            cases.each { c ->
                def (component, target, expected) = c
                assertEquals("${component} on ${target}", expected, branch(script, component, target))
            }
        }

        @Test
        void shouldShipValidConfigWithExpectedMappings() {
            def file = new File('libraries/tipipeline/resources/configs/component-branch-mapping.yaml')
            assertTrue('config resource must exist', file.exists())

            def config = new groovy.yaml.YamlSlurper().parse(file)
            def mappings = config['mappings']
            assertTrue('mappings must be a non-empty list', mappings instanceof List && !mappings.isEmpty())

            def byMatch = mappings.findAll { it instanceof Map && it['match'] != null }
                .collectEntries { [(it['match'].toString()): it] }
            ['feature/release-8.5-materialized-view',
             'feature/release-8.5.5-active-active',
             'feature/release-8.5-fts'].each { m ->
                assertTrue("config must contain mapping for ${m}", byMatch.containsKey(m))
            }
            assertEquals('$self', byMatch['feature/release-8.5-materialized-view']['default'])
            assertEquals('$release', byMatch['feature/release-8.5.5-active-active']['default'])
            assertEquals('release-8.5-20260121-v8.5.5',
                byMatch['feature/release-8.5.5-active-active']['components']['tidb-test'])
            assertEquals('$self', byMatch['feature/release-8.5-fts']['components']['tidb'])
            assertEquals('release-fts-202602', byMatch['feature/release-8.5-fts']['components']['tici'])
        }
    }

    // ============================================================
    // Regression: preserve the legacy branch matching captured before the
    // component-branch-mapping.yaml refactor. The golden tables below were
    // generated from the pre-refactor implementation; a mismatch means the
    // refactor changed behavior.
    // ============================================================
    static class LegacyBranchMatchingRegression {
        private def script

        @Before
        void setUp() {
            script = loadScriptWithBindings([
                libraryResource: { String path -> 'mappings: []' },
                readYaml: { Map args ->
                    new groovy.yaml.YamlSlurper()
                        .parse(new File('libraries/tipipeline/resources/configs/component-branch-mapping.yaml'))
                },
            ])
        }

        private String branch(String component, String target,
                              String title = 'feat: x', String trunk = 'master') {
            script.invokeMethod('computeBranchFromPR', [component, target, title, trunk])
        }

        @Test
        void shouldPreserveLegacyBranchDerivation() {
            def cases = [
                // component, target branch, expected  (title='feat: x', trunk='master')
                ['tidb', 'master', 'master'],
                ['tidb', 'release-8.5', 'release-8.5'],
                ['tidb', 'release-8.5-beta.1', 'release-8.5-beta.1'],
                ['tidb', 'release-6.2-20220801', 'release-6.2'],
                ['tidb', 'release-8.5-20230101-v8.5.1', 'release-8.5'],
                ['tidb', 'release-6.1-20230101-v6.1.2', 'release-6.1'],
                ['tidb', 'feature/release-8.5-abc', 'release-8.5'],
                ['tidb', 'feature_release-8.1-xyz', 'release-8.1'],
                ['tidb', 'feature/release-8.5-materialized-view', 'feature/release-8.5-materialized-view'],
                // feature/release-8.5-fts intentionally deviates from the legacy
                // behavior; it is covered by FtsBranchMapping.
                ['tidb', 'feature/release-8.5.5-active-active', 'feature/release-8.5.5-active-active'],
                ['tidb', 'feature/release-8.5.5-abc', 'release-8.5'],
                ['tidb', 'feature/release-8.1.1-xyz', 'release-8.1'],
                ['tidb', 'feature/my-feature', 'master'],
                ['tidb', 'feature_my-feature', 'master'],
                ['tidb', 'release-nextgen-202603', 'release-nextgen-202603'],
                ['tidb', 'release-nextgen-20260301', 'release-nextgen-20260301'],
                ['tidb', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['tidb', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
                ['tikv', 'master', 'master'],
                ['tikv', 'release-8.5', 'release-8.5'],
                ['tikv', 'release-8.5-beta.1', 'release-8.5-beta.1'],
                ['tikv', 'release-6.2-20220801', 'release-6.2'],
                ['tikv', 'release-8.5-20230101-v8.5.1', 'release-8.5'],
                ['tikv', 'release-6.1-20230101-v6.1.2', 'release-6.1'],
                ['tikv', 'feature/release-8.5-abc', 'release-8.5'],
                ['tikv', 'feature_release-8.1-xyz', 'release-8.1'],
                ['tikv', 'feature/release-8.5-materialized-view', 'feature/release-8.5-materialized-view'],
                ['tikv', 'feature/release-8.5.5-active-active', 'release-8.5'],
                ['tikv', 'feature/release-8.5.5-abc', 'release-8.5'],
                ['tikv', 'feature/release-8.1.1-xyz', 'release-8.1'],
                ['tikv', 'feature/my-feature', 'master'],
                ['tikv', 'feature_my-feature', 'master'],
                ['tikv', 'release-nextgen-202603', 'release-nextgen-202603'],
                ['tikv', 'release-nextgen-20260301', 'release-nextgen-20260301'],
                ['tikv', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['tikv', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
                ['ticdc', 'master', 'master'],
                ['ticdc', 'release-8.5', 'release-8.5'],
                ['ticdc', 'release-8.5-beta.1', 'release-8.5-beta.1'],
                ['ticdc', 'release-6.2-20220801', 'release-6.2'],
                ['ticdc', 'release-8.5-20230101-v8.5.1', 'release-8.5'],
                ['ticdc', 'release-6.1-20230101-v6.1.2', 'release-6.1'],
                ['ticdc', 'feature/release-8.5-abc', 'release-8.5'],
                ['ticdc', 'feature_release-8.1-xyz', 'release-8.1'],
                ['ticdc', 'feature/release-8.5-materialized-view', 'release-8.5'],
                ['ticdc', 'feature/release-8.5.5-active-active', 'feature/release-8.5.5-active-active'],
                ['ticdc', 'feature/release-8.5.5-abc', 'release-8.5'],
                ['ticdc', 'feature/release-8.1.1-xyz', 'release-8.1'],
                ['ticdc', 'feature/my-feature', 'master'],
                ['ticdc', 'feature_my-feature', 'master'],
                ['ticdc', 'release-nextgen-202603', 'release-nextgen-202603'],
                ['ticdc', 'release-nextgen-20260301', 'release-nextgen-20260301'],
                ['ticdc', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['ticdc', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
                ['tidb-test', 'master', 'master'],
                ['tidb-test', 'release-8.5', 'release-8.5'],
                ['tidb-test', 'release-8.5-beta.1', 'release-8.5-beta.1'],
                ['tidb-test', 'release-6.2-20220801', 'release-6.2'],
                ['tidb-test', 'release-8.5-20230101-v8.5.1', 'release-8.5.1'],
                ['tidb-test', 'release-6.1-20230101-v6.1.2', 'release-6.1.2'],
                ['tidb-test', 'feature/release-8.5-abc', 'release-8.5'],
                ['tidb-test', 'feature_release-8.1-xyz', 'release-8.1'],
                ['tidb-test', 'feature/release-8.5-materialized-view', 'feature/release-8.5-materialized-view'],
                ['tidb-test', 'feature/release-8.5.5-active-active', 'release-8.5-20260121-v8.5.5'],
                ['tidb-test', 'feature/release-8.5.5-abc', 'release-8.5.5'],
                ['tidb-test', 'feature/release-8.1.1-xyz', 'release-8.1.1'],
                ['tidb-test', 'feature/my-feature', 'master'],
                ['tidb-test', 'feature_my-feature', 'master'],
                ['tidb-test', 'release-nextgen-202603', 'release-nextgen-202603'],
                ['tidb-test', 'release-nextgen-20260301', 'release-nextgen-20260301'],
                ['tidb-test', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['tidb-test', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
                ['plugin', 'master', 'master'],
                ['plugin', 'release-8.5', 'release-8.5'],
                ['plugin', 'release-8.5-beta.1', 'release-8.5-beta.1'],
                ['plugin', 'release-6.2-20220801', 'release-6.2'],
                ['plugin', 'release-8.5-20230101-v8.5.1', 'release-8.5.1'],
                ['plugin', 'release-6.1-20230101-v6.1.2', 'release-6.1.2'],
                ['plugin', 'feature/release-8.5-abc', 'release-8.5'],
                ['plugin', 'feature_release-8.1-xyz', 'release-8.1'],
                ['plugin', 'feature/release-8.5-materialized-view', 'feature/release-8.5-materialized-view'],
                ['plugin', 'feature/release-8.5.5-active-active', 'release-8.5.5'],
                ['plugin', 'feature/release-8.5.5-abc', 'release-8.5.5'],
                ['plugin', 'feature/release-8.1.1-xyz', 'release-8.1.1'],
                ['plugin', 'feature/my-feature', 'master'],
                ['plugin', 'feature_my-feature', 'master'],
                ['plugin', 'release-nextgen-202603', 'release-nextgen-202603'],
                ['plugin', 'release-nextgen-20260301', 'release-nextgen-20260301'],
                ['plugin', 'release-nextgen-25.10-20251123', 'release-nextgen-20251011'],
                ['plugin', 'release-nextgen-26.3.9-20260817', 'release-nextgen-202603'],
            ]
            cases.each { c ->
                def (component, target, expected) = c
                assertEquals("${component} on ${target}", expected, branch(component, target))
            }
        }

        @Test
        void shouldPreserveLegacyTitleAndTrunkHandling() {
            def cases = [
                // component, target branch, title, trunk, expected
                ['tidb', 'master', 'feat: x | tidb=pr/123', 'master', 'pr/123'],
                ['pd', 'release-8.5', 'feat: x | pd=@v8.5.0', 'master', '@v8.5.0'],
                ['tikv', 'feature/release-8.5-fts', 'feat: x | tikv=release-9.0', 'master', 'release-9.0'],
                ['tidb-test', 'feature/release-8.5-fts', 'feat: x | tidb-test=pr/999', 'master', 'pr/999'],
                ['tidb', 'master', 'feat: x (#123) | tidb=release-8.5', 'master', 'release-8.5'],
                ['tidb', 'release-8.5', 'feat: x (#123) | tidb=release-9.0', 'master', 'release-9.0'],
                ['tidb', 'feature/my-feature', 'feat: x', 'release-8.5', 'release-8.5'],
                ['tidb', 'feature/release-8.5.5-abc', 'feat: x', 'release-8.5', 'release-8.5'],
            ]
            cases.each { c ->
                def (component, target, title, trunk, expected) = c
                assertEquals("${component} on ${target} (${title}, trunk=${trunk})", expected,
                    branch(component, target, title, trunk))
            }
        }
    }

    // ============================================================
    // feature/release-8.5-fts: the listed components consume each other's
    // code/binaries from the feature branch itself (intentional deviation
    // from the legacy release-8.5 default).
    // ============================================================
    static class FtsBranchMapping {
        private static final String BRANCH = 'feature/release-8.5-fts'
        private def script

        @Before
        void setUp() {
            script = loadScriptWithBindings([
                libraryResource: { String path -> 'mappings: []' },
                readYaml: { Map args ->
                    new groovy.yaml.YamlSlurper()
                        .parse(new File('libraries/tipipeline/resources/configs/component-branch-mapping.yaml'))
                },
            ])
        }

        private String branch(String component, String title = 'feat: x') {
            script.invokeMethod('computeBranchFromPR', [component, BRANCH, title, 'master'])
        }

        @Test
        void shouldUseFeatureBranchForAllPeerComponents() {
            ['tidb', 'pd', 'tiflash', 'tikv', 'ticdc', 'tidb-test'].each { component ->
                assertEquals("${component} on ${BRANCH}", BRANCH, branch(component))
            }
        }

        @Test
        void shouldUseReleaseFts202602ForTici() {
            assertEquals('release-fts-202602', branch('tici'))
        }

        @Test
        void shouldKeepReleaseDefaultForOtherComponents() {
            assertEquals('release-8.5', branch('tiproxy'))
            assertEquals('release-8.5', branch('tiflow'))
        }

        @Test
        void shouldLetPrTitleParamOverrideTheMapping() {
            assertEquals('pr/123', branch('pd', 'feat: x | pd=pr/123'))
            assertEquals('@v8.5.0', branch('tikv', 'feat: x | tikv=@v8.5.0'))
            assertEquals('release-fts-202603', branch('tici', 'feat: x | tici=release-fts-202603'))
        }

        @Test
        void shouldDeriveFeatureBranchOciTag() {
            // computeArtifactOciTagFromPR replaces '/' with '-' to form the OCI tag.
            ['tidb', 'pd', 'tiflash', 'tikv', 'ticdc', 'tidb-test'].each { component ->
                def tag = script.invokeMethod('computeArtifactOciTagFromPR',
                    [component, BRANCH, 'feat: x', 'master'])
                assertEquals("OCI tag for ${component}", 'feature-release-8.5-fts', tag)
            }
            // tici is pinned to release-fts-202602, which is used as-is.
            assertEquals('release-fts-202602',
                script.invokeMethod('computeArtifactOciTagFromPR', ['tici', BRANCH, 'feat: x', 'master']))
        }
    }

}
