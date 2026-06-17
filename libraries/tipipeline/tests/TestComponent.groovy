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
}
