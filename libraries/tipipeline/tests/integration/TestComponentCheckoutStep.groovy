import org.junit.After
import org.junit.Before
import org.junit.Test
import static org.junit.Assert.*

/**
 * Execute the broker checkout shell from component.groovy against local Git
 * repositories. Only the credential scope is stubbed; Git itself is real.
 *
 * Usage: groovy libraries/tipipeline/tests/integration/TestComponentCheckoutStep.groovy
 */
class TestComponentCheckoutStep {
    private File root
    private File remote
    private def component
    private String firstCommit
    private String secondCommit

    // Propagate assertion failures to the presubmit's shell exit status.
    static void main(String[] args) {
        org.junit.runner.JUnitCore.main(TestComponentCheckoutStep.name)
    }

    @Before
    void setUp() {
        root = File.createTempDir('component-checkout-', '')
        remote = new File(root, 'remote.git')
        final seed = new File(root, 'seed')
        run(root, ['git', 'init', '--bare', remote.absolutePath])
        run(root, ['git', 'init', seed.absolutePath])
        git(seed, 'config', 'user.email', 'test@example.com')
        git(seed, 'config', 'user.name', 'Checkout Test')
        git(seed, 'symbolic-ref', 'HEAD', 'refs/heads/release-8.5')
        final version = new File(seed, 'version')
        version.text = 'A\n'
        git(seed, 'add', 'version')
        git(seed, 'commit', '-m', 'A')
        firstCommit = git(seed, 'rev-parse', 'HEAD')
        version.text = 'B\n'
        git(seed, 'commit', '-am', 'B')
        secondCommit = git(seed, 'rev-parse', 'HEAD')
        git(seed, 'push', remote.absolutePath, 'HEAD:refs/heads/release-8.5')
        git(remote, 'update-ref', 'refs/tags/stable', firstCommit)
        git(remote, 'update-ref', 'refs/pull/259/head', secondCommit)
        git(remote, 'symbolic-ref', 'HEAD', 'refs/heads/release-8.5')

        final prow = new Expando()
        prow.withGitAskPassFromBroker = { Map cfg, Closure body -> body() }
        component = new GroovyShell(new Binding([prow: prow])).parse(
            new File('libraries/tipipeline/vars/component.groovy'))
    }

    @After
    void tearDown() {
        root?.deleteDir()
    }

    private String run(File directory, List<String> command) {
        final builder = new ProcessBuilder(command)
        builder.directory(directory)
        builder.redirectErrorStream(true)
        // Ignore host Git configuration/credentials and forbid network transports.
        builder.environment().clear()
        builder.environment().putAll([
            PATH: System.getenv('PATH') ?: '/usr/bin:/bin',
            HOME: root.absolutePath,
            GIT_CONFIG_GLOBAL: '/dev/null',
            GIT_CONFIG_NOSYSTEM: '1',
            GIT_ALLOW_PROTOCOL: 'file',
        ])
        final process = builder.start()
        final output = process.inputStream.text
        assertEquals("${command.join(' ')} failed in ${directory}:\n${output}",
            0, process.waitFor())
        return output.trim()
    }

    private String git(File directory, String... args) {
        return run(directory, ['git'] + args.toList())
    }

    private void checkout(File directory, String branch,
                          String refspec = '+refs/heads/*:refs/remotes/origin/*') {
        directory.mkdirs()
        component.binding.setVariable('sh', { Map step ->
            run(directory, ['bash', '-c', step.script.toString()])
        })
        component.invokeMethod('checkoutWithAuth', [[scm: [
            branches: [[name: branch]],
            extensions: [[$class: 'CloneOption', timeout: 1]],
            userRemoteConfigs: [[url: remote.absolutePath, refspec: refspec]],
        ]], 'test/component'] as Object[])
    }

    @Test
    void shouldRefreshCachedBranchesToTheFetchedRemoteCommit() {
        final cases = [
            [name: 'remote advances', branch: 'release-8.5',
             cached: firstCommit, fetched: secondCommit],
            [name: 'remote force-resets', branch: 'release-8.5',
             cached: secondCommit, fetched: firstCommit],
            [name: 'branch with slash', branch: 'feature/cache-refresh',
             cached: firstCommit, fetched: secondCommit],
        ]
        cases.eachWithIndex { c, index ->
            final directory = new File(root, "cached-${index}")
            final remoteRef = "refs/heads/${c.branch}".toString()
            git(remote, 'update-ref', remoteRef, c.cached)
            run(root, ['git', 'clone', '--branch', c.branch, remote.absolutePath, directory.absolutePath])
            assertEquals("${c.name}: cached checkout", c.cached, git(directory, 'rev-parse', 'HEAD'))

            git(remote, 'update-ref', remoteRef, c.fetched)
            checkout(directory, c.branch)

            assertEquals("${c.name}: fetch must update the remote-tracking ref", c.fetched,
                git(directory, 'rev-parse', "refs/remotes/origin/${c.branch}".toString()))
            assertEquals("${c.name}: checkout must not reuse the cached commit", c.fetched,
                git(directory, 'rev-parse', 'HEAD'))
            assertEquals("${c.name}: local branch must also advance", c.fetched,
                git(directory, 'rev-parse', "refs/heads/${c.branch}".toString()))
        }
    }

    @Test
    void shouldCreateFreshLocalBranchesFromFetchedRefs() {
        git(remote, 'update-ref', 'refs/heads/feature/cache-refresh', secondCommit)
        ['release-8.5', 'feature/cache-refresh'].eachWithIndex { branch, index ->
            final directory = new File(root, "fresh-${index}")
            checkout(directory, branch)
            assertEquals("${branch}: fresh checkout must use the remote commit", secondCommit,
                git(directory, 'rev-parse', 'HEAD'))
            assertEquals("${branch}: fresh checkout must create the local branch", "refs/heads/${branch}".toString(),
                git(directory, 'symbolic-ref', 'HEAD'))
        }
    }

    @Test
    void shouldPreserveDetachedTagCommitAndPullRequestCheckouts() {
        final cases = [
            [name: 'tag', branch: 'stable', expected: firstCommit,
             refspec: '+refs/heads/*:refs/remotes/origin/*'],
            [name: 'commit', branch: firstCommit, expected: firstCommit,
             refspec: '+refs/heads/*:refs/remotes/origin/*'],
            [name: 'pull request', branch: 'origin/pr/259/head', expected: secondCommit,
             refspec: '+refs/pull/*:refs/remotes/origin/pr/*'],
        ]
        cases.eachWithIndex { c, index ->
            final directory = new File(root, "fallback-${index}")
            checkout(directory, c.branch, c.refspec)
            assertEquals("${c.name}: checkout must resolve the requested ref", c.expected,
                git(directory, 'rev-parse', 'HEAD'))
            assertEquals("${c.name}: checkout must stay detached", 'HEAD',
                git(directory, 'rev-parse', '--abbrev-ref', 'HEAD'))
        }
    }

    @Test
    void shouldPreferTheRemoteBranchWhenATagHasTheSameName() {
        git(remote, 'update-ref', 'refs/heads/stable', secondCommit)
        final directory = new File(root, 'same-name')

        checkout(directory, 'stable')

        assertEquals('the remote branch must take priority over the older tag', secondCommit,
            git(directory, 'rev-parse', 'HEAD'))
        assertEquals('the checkout must create the local branch', 'refs/heads/stable',
            git(directory, 'symbolic-ref', 'HEAD'))
        assertEquals('the tag itself must not move', firstCommit,
            git(directory, 'rev-parse', 'refs/tags/stable'))
    }
}
