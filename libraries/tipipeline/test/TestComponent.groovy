import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.experimental.runners.Enclosed
import static org.junit.Assert.*

/**
 * Unit tests for component.groovy utility functions.
 *
 * Usage:
 *   groovy libraries/tipipeline/test/TestComponent.groovy
 */
@RunWith(Enclosed.class)
class TestComponent {

    // ============================================================
    // Shared: load script once via a helper
    // ============================================================
    private static def loadScript() {
        new GroovyShell().parse(
            new File("libraries/tipipeline/vars/component.groovy"))
    }

    // ============================================================
    // Tests for parseCIParamsFromPRTitle
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

        // ---------- Supported inputs ----------

        @Test
        void testSingleParam() {
            assertEquals(
                [tidb: 'pr/123'],
                parseCIParams('feat: xxx | tidb=pr/123'))
        }

        @Test
        void testMultipleParams() {
            assertEquals(
                [tidb: 'pr/123', pd: '@v8.5.0'],
                parseCIParams('feat: xxx | tidb=pr/123 pd=@v8.5.0'))
        }

        @Test
        void testPRnumberBeforePipeNoSpace() {
            assertEquals(
                [tidb: 'pr/123'],
                parseCIParams('feat: xxx (#12467)| tidb=pr/123'))
        }

        @Test
        void testPRnumberBeforePipeWithSpace() {
            assertEquals(
                [tidb: 'pr/123', pd: '@v8.5.0'],
                parseCIParams('feat: xxx (#12467) | tidb=pr/123 pd=@v8.5.0'))
        }

        @Test
        void testMultiplePipesTakeLastSegment() {
            assertEquals(
                [tidb: 'pr/456', pd: '@v8.5.0'],
                parseCIParams('feat: xxx | tidb=pr/123 (#12456) | tidb=pr/456 pd=@v8.5.0'))
        }

        @Test
        void testNoPipeReturnsEmpty() {
            assertTrue(parseCIParams('feat: xxx').isEmpty())
        }

        @Test
        void testPRnumberOnlyReturnsEmpty() {
            assertTrue(parseCIParams('feat: xxx (#12467)').isEmpty())
        }

        // ---------- Rejected (cherry-pick suffix) ----------

        @Test
        void testCherryPickSuffixRejected() {
            assertTrue(
                parseCIParams('feat: xxx | tidb=pr/123 pd=@v8.5.0 (#1235)').isEmpty())
        }

        @Test
        void testCherryPickSingleParamRejected() {
            assertTrue(
                parseCIParams('feat: xxx | tidb=pr/123 (#12456)').isEmpty())
        }

        // ---------- Edge cases ----------

        @Test
        void testAtPrefixValue() {
            assertEquals(
                [pd: '@v8.5.0'],
                parseCIParams('feat: xxx | pd=@v8.5.0'))
        }

        @Test
        void testVersionTagValue() {
            assertEquals(
                [tidb: 'v8.5.0'],
                parseCIParams('feat: xxx | tidb=v8.5.0'))
        }

        @Test
        void testHyphenInComponentName() {
            assertEquals(
                ['my-component': 'test'],
                parseCIParams('feat: xxx | my-component=test'))
        }

        @Test
        void testSpacesAroundEquals() {
            assertEquals(
                [tidb: 'pr/123'],
                parseCIParams('feat: xxx | tidb = pr/123'))
        }

        @Test
        void testEmptyAfterPipe() {
            assertTrue(parseCIParams('feat: xxx |  ').isEmpty())
        }
    }

    // ============================================================
    // Tests for computeBranchFromPR
    // ============================================================
    static class ComputeBranchFromPR {

        private def script

        @Before
        void setUp() {
            script = loadScript()
        }

        /** Convenience wrapper to call computeBranchFromPR. */
        private String branch(String component, String targetBranch,
                              String title, String trunk = 'master') {
            script.invokeMethod('computeBranchFromPR',
                [component, targetBranch, title, trunk])
        }

        // ---------- PR title specifies component branch ----------

        @Test
        void testParamPR() {
            assertEquals('pr/123',
                branch('tidb', 'master', 'feat: xx | tidb=pr/123'))
        }

        @Test
        void testParamReleaseBranch() {
            assertEquals('release-8.5',
                branch('tidb', 'master', 'feat: xx | tidb=release-8.5'))
        }

        @Test
        void testParamCommitSHA() {
            assertEquals('abc123def456abc123def456abc123def456abc1',
                branch('tidb', 'master',
                    'feat: xx | tidb=abc123def456abc123def456abc123def456abc1'))
        }

        // ---------- Cherry-pick: PR number suffix ignores param ----------

        @Test
        void testCherryPickIgnoresParam() {
            // Title ends with (#N), param should be ignored → fallback to target branch
            assertEquals('master',
                branch('tidb', 'master',
                    'feat: xx | tidb=release-8.5 (#12345)'))
        }

        @Test
        void testCherryPickOnReleaseBranch() {
            assertEquals('release-8.5',
                branch('tidb', 'release-8.5',
                    'feat: xx | tidb=pr/100 (#12345)'))
        }

        @Test
        void testCherryPickMixedParams() {
            assertEquals('pr/200',
                branch('tidb', 'release-8.5',
                    'feat: xx | tidb=pr/100 (#12345) | tidb=pr/200 pd=pr/300'))
        }

        // ---------- No param in title → branch derivation ----------

        @Test
        void testMasterBranch() {
            assertEquals('master',
                branch('tidb', 'master', 'feat: xx'))
        }

        @Test
        void testReleaseBranchNoParam() {
            assertEquals('release-8.5',
                branch('tidb', 'release-8.5', 'feat: xx'))
        }

        @Test
        void testReleaseBranchWithBeta() {
            assertEquals('release-8.5-beta.1',
                branch('tidb', 'release-8.5-beta.1', 'feat: xx'))
        }

        @Test
        void testHotfixBranchFallsBackToRelease() {
            assertEquals('release-6.2',
                branch('tidb', 'release-6.2-20220801', 'feat: xx'))
        }

        @Test
        void testFeatureBranchFallsBackToTrunk() {
            assertEquals('master',
                branch('tidb', 'feature/my-feature', 'feat: xx'))
        }

        // ---------- Component-specific behavior ----------

        @Test
        void testNewHotfixBranchForPatchComponent() {
            // tidb-test supports patch release branch (release-X.Y.Z)
            assertEquals('release-8.5.1',
                branch('tidb-test', 'release-8.5-20230101-v8.5.1',
                    'feat: xx'))
        }

        @Test
        void testNewHotfixBranchForNonPatchComponent() {
            // tidb does NOT support patch release branch (falls back to release-X.Y)
            assertEquals('release-8.5',
                branch('tidb', 'release-8.5-20230101-v8.5.1',
                    'feat: xx'))
        }
    }
}
