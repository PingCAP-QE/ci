/*
 * Runs the `Update submodules` step instead of asserting on its text.
 *
 * The step is where library code meets a real filesystem: it runs inside
 * `dir(REFS.repo)` and reaches for files by path. PingCAP-QE/ci#5568 installed
 * the SSH guard one directory away from where the step executed it — every
 * string-level assertion stayed green while every job with a submodule died at
 * `fatal: cannot exec '<workspace>/git_ssh_guard.sh': No such file or directory`.
 *
 * So this file renders the step, lays out a workspace shaped like the one the
 * job gets (repository checkout one level below the workspace root), and runs it
 * with a stub `git` that enforces the same contract the real one does: whatever
 * `GIT_SSH_COMMAND` names has to exist and be executable, or the update fails.
 *
 * `git`, `ssh` and `curl` are stubs, so nothing here touches the network. The
 * step is executed with `/bin/sh` so the check also works in the
 * `groovy:*-jdk17` image the presubmit uses.
 *
 * Usage:
 *   groovy libraries/tipipeline/tests/TestProwCheckoutStep.groovy
 */
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

import org.junit.Test

class TestProwCheckoutStep {
    private static final String STUB_GIT = '''#!/bin/sh
# Enough of git for the submodule step: the update is where the transport the
# step chose becomes an exec, which is what broke in PingCAP-QE/ci#5568.
case "$1" in
    submodule)
        case "${GIT_SSH_COMMAND:-}" in
            '')
                echo "fake git: no GIT_SSH_COMMAND was exported for the update" >&2
                exit 1
                ;;
        esac
        set -- ${GIT_SSH_COMMAND}
        transport="$1"
        if [ -e "$transport" ]; then
            if [ ! -x "$transport" ]; then
                echo "fatal: cannot exec '$transport': Permission denied" >&2
                exit 1
            fi
        elif ! command -v "$transport" >/dev/null 2>&1; then
            echo "fatal: cannot exec '$transport': No such file or directory" >&2
            echo "fatal: unable to fork" >&2
            exit 1
        fi
        echo "fake git: submodule update through $transport"
        exit 0
        ;;
esac
exit 0
'''

    private static final String STUB_BIN = '''#!/bin/sh
exit 0
'''

    private static def loadProw(Map steps = [:]) {
        def binding = new Binding()
        def env = steps.containsKey('env') ? steps['env'] : [:]
        if (!env.containsKey('JOB_NAME')) {
            env = env + [JOB_NAME: 'pingcap/tidb/release-8.5/pull_build']
        }
        steps.each { name, value -> binding.setVariable(name, value) }
        binding.setVariable('env', env)
        new GroovyShell(binding).parse(new File('libraries/tipipeline/vars/prow.groovy'))
    }

    /**
     * A workspace shaped like a real build: `workspace/` is $WORKSPACE, the
     * checkout it hands to the pipeline is `workspace/tidb`, and the step runs
     * with the checkout as its working directory (`dir(REFS.repo)`).
     */
    private static class Workspace {
        File root
        File checkout
        File home
        File bin
        int lastExitCode = -1

        Workspace() {
            root = File.createTempDir('prow-step-ws', '')
            checkout = new File(root, 'tidb')
            checkout.mkdirs()
            home = new File(root, 'home')
            home.mkdirs()
            bin = new File(root, 'bin')
            bin.mkdirs()
            new File(bin, 'git').with {
                text = STUB_GIT
                setExecutable(true)
            }
            ['ssh', 'curl'].each { name ->
                new File(bin, name).with {
                    text = STUB_BIN
                    setExecutable(true)
                }
            }
        }

        Map<String, String> environment() {
            final inherited = System.getenv('PATH') ?: '/usr/bin:/bin'
            final values = [HOME             : home.absolutePath,
                            WORKSPACE        : root.absolutePath,
                            PATH             : "${bin.absolutePath}:${inherited}",
                            // Keep the stub `git` from inheriting a real credential setup.
                            GIT_CONFIG_GLOBAL: '/dev/null']
            values.collectEntries { key, value -> [key, value.toString()] }
        }

        /** Execute a script the way the pipeline would: from the checkout. */
        String run(String script) {
            final file = new File(root, 'step.sh')
            file.text = script
            final pb = new ProcessBuilder(['/bin/sh', file.absolutePath])
            pb.directory(checkout)
            pb.redirectErrorStream(true)
            pb.environment().clear()
            pb.environment().putAll(environment())
            final process = pb.start()
            final output = process.inputStream.text
            process.waitFor()
            lastExitCode = process.exitValue()
            return output
        }
    }

    /** Render the submodule step for one `withSubmodule` checkout. */
    private static Map renderSubmoduleStep(Workspace ws) {
        final events = []
        final written = [:]
        final script = loadProw(
            env: [JOB_NAME: 'pingcap/tidb/release-8.5/pull_build', WORKSPACE: ws.root.absolutePath],
            readFile: { Map args -> '' },
            libraryResource: { String path -> '#!/bin/sh\nexit 0\n' },
            // Jenkins resolves a relative name against the step's current
            // directory, which is the checkout here — not $WORKSPACE.
            writeFile: { Map args ->
                final target = new File(args.file).absolute ? new File(args.file) : new File(ws.checkout, args.file)
                target.parentFile?.mkdirs()
                target.text = args.text
                written[args.file] = target.absolutePath
            },
            sshagent: { Map args, Closure body -> body() },
            sh: { Map args -> events << (args.script ?: '') })

        script.checkoutRefs([org: 'pingcap', repo: 'tidb', base_ref: 'release-8.5',
                             base_sha: 'abcdef1234567890',
                             pulls: [[number: 12, sha: '1234567890abcdef']]],
            'github-bot-ssh', 7, true)

        final update = events.find { it.contains('git submodule update') }
        if (update == null) {
            fail('the checkout did not render a submodule update step')
        }
        return [script: update, written: written]
    }

    @Test
    void shouldRunTheSubmoduleUpdateFromInsideTheCheckoutDirectory() {
        final ws = new Workspace()
        final rendered = renderSubmoduleStep(ws)

        final output = ws.run(rendered.script)

        assertEquals("the step failed:\n${output}", 0, ws.lastExitCode)
        assertTrue("the step announced itself and fetched:\n${output}",
            output.contains('update submodules') && output.contains('fake git: submodule update'))
        assertTrue('the step seeds the SSH host keys before the fetch',
            new File(ws.home, '.ssh').isDirectory())
    }

    @Test
    void shouldInstallTheTransportWhereTheUpdateExecutesIt() {
        final ws = new Workspace()
        final rendered = renderSubmoduleStep(ws)
        final match = rendered.script =~ /GIT_SSH_COMMAND="([^"]+)"/
        if (!match.find()) {
            fail('the update exports no GIT_SSH_COMMAND')
        }
        final command = match.group(1)
        if (!command.contains('/')) {
            // The transport is a program on PATH (`ssh -o …`), so there is no
            // installed file to check — the run above already proved the exec.
            return
        }

        assertTrue("the update hands the fetch to ${command}, which the step must install",
            rendered.written.values().any { it == command })
        final transport = new File(command)
        assertTrue("${command} must exist after the step ran", transport.isFile())
        assertTrue("${command} must be executable", transport.canExecute())
    }

    @Test
    void shouldFailWhenTheTransportIsInstalledOneDirectoryAway() {
        // Negative control: the shape of PingCAP-QE/ci#5568. If the stub git
        // stopped enforcing `GIT_SSH_COMMAND`, this test would pass and every
        // test above it would stop meaning anything.
        final ws = new Workspace()
        final output = ws.run("""#!/usr/bin/env bash
set -e
mkdir -p "${ws.checkout.absolutePath}"
printf '#!/bin/sh\\nexit 0\\n' > "${ws.checkout.absolutePath}/git_ssh_guard.sh"
chmod +x "${ws.checkout.absolutePath}/git_ssh_guard.sh"
GIT_SSH_COMMAND="${ws.root.absolutePath}/git_ssh_guard.sh" git submodule update --init --recursive
""")

        assertTrue("the update must fail when the guard is one directory away:\n${output}",
            ws.lastExitCode != 0)
        assertTrue("the failure must look like git's:\n${output}",
            output.contains("cannot exec '${ws.root.absolutePath}/git_ssh_guard.sh': No such file or directory"))
    }
}
