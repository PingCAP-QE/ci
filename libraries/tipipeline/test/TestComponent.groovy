import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Table-driven unit tests for component.groovy utility functions.
 *
 * Usage:
 *   groovy libraries/tipipeline/test/TestComponent.groovy
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
