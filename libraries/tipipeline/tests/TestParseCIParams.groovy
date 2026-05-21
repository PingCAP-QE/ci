import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

import static org.junit.Assert.*

/**
 * Unit tests for parseCIParamsFromPRTitle in component.groovy.
 *
 * Usage:
 *   groovy libraries/tipipeline/tests/TestParseCIParams.groovy
 */
@RunWith(Suite.class)
@SuiteClasses([
    TestParseCIParams.ValidInputs,
    TestParseCIParams.InvalidInputs,
])
class TestParseCIParams {

    private static def loadScript() {
        new GroovyShell().parse(
            new File("libraries/tipipeline/vars/component.groovy"))
    }

    static class ValidInputs {

        private def script

        @Before
        void setUp() {
            script = TestParseCIParams.loadScript()
        }

        private Map parseCIParams(String prTitle) {
            script.invokeMethod('parseCIParamsFromPRTitle', [prTitle]) as Map
        }

        @Test
        void shouldExtractParamsFromSupportedTitlePatterns() {
            def cases = [
                [
                    desc: 'single param after pipe',
                    title: 'feat: xxx | tidb=pr/123',
                    expected: [tidb: 'pr/123'],
                ],
                [
                    desc: 'multiple params after pipe',
                    title: 'feat: xxx | tidb=pr/123 pd=@v8.5.0',
                    expected: [tidb: 'pr/123', pd: '@v8.5.0'],
                ],
                [
                    desc: 'pr number before pipe without extra spaces',
                    title: 'feat: xxx (#12467)| tidb=pr/123',
                    expected: [tidb: 'pr/123'],
                ],
                [
                    desc: 'pr number before pipe with spaces',
                    title: 'feat: xxx (#12467) | tidb=pr/123 pd=@v8.5.0',
                    expected: [tidb: 'pr/123', pd: '@v8.5.0'],
                ],
                [
                    desc: 'last pipe segment wins',
                    title: 'feat: xxx | tidb=pr/123 (#12456) | tidb=pr/456 pd=@v8.5.0',
                    expected: [tidb: 'pr/456', pd: '@v8.5.0'],
                ],
                [
                    desc: 'accept at-prefixed values',
                    title: 'feat: xxx | pd=@v8.5.0',
                    expected: [pd: '@v8.5.0'],
                ],
                [
                    desc: 'accept version-like values',
                    title: 'feat: xxx | tidb=v8.5.0',
                    expected: [tidb: 'v8.5.0'],
                ],
                [
                    desc: 'accept component names with hyphen',
                    title: 'feat: xxx | my-component=test',
                    expected: ['my-component': 'test'],
                ],
                [
                    desc: 'ignore spaces around equals',
                    title: 'feat: xxx | tidb = pr/123',
                    expected: [tidb: 'pr/123'],
                ],
            ]

            cases.each { c ->
                assertEquals(c.desc, c.expected, parseCIParams(c.title))
            }
        }
    }

    static class InvalidInputs {

        private def script

        @Before
        void setUp() {
            script = TestParseCIParams.loadScript()
        }

        private Map parseCIParams(String prTitle) {
            script.invokeMethod('parseCIParamsFromPRTitle', [prTitle]) as Map
        }

        @Test
        void shouldReturnEmptyWhenTitleDoesNotProvideUsableCIParams() {
            def cases = [
                [
                    desc: 'title without pipe',
                    title: 'feat: xxx',
                ],
                [
                    desc: 'title with pr number only',
                    title: 'feat: xxx (#12467)',
                ],
                [
                    desc: 'pipe followed by whitespace only',
                    title: 'feat: xxx |  ',
                ],
                [
                    desc: 'cherry-pick suffix on last pipe segment',
                    title: 'feat: xxx | tidb=pr/123 pd=@v8.5.0 (#1235)',
                ],
                [
                    desc: 'single param with cherry-pick suffix',
                    title: 'feat: xxx | tidb=pr/123 (#12456)',
                ],
            ]

            cases.each { c ->
                assertTrue(c.desc, parseCIParams(c.title).isEmpty())
            }
        }
    }
}
