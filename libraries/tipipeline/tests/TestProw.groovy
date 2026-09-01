/**
 * Exercise the Jenkins-independent parts of prow.groovy without a Jenkins
 * controller. The script form is intentional: assertion or parser failures
 * must propagate as a non-zero process exit code in the Prow test job.
 */
def loadProw = { Map steps = [:] ->
    def binding = new Binding()
    steps.each { name, value -> binding.setVariable(name, value) }
    new GroovyShell(binding).parse(new File("libraries/tipipeline/vars/prow.groovy"))
}

def refs = {
    [
        org: 'pingcap',
        repo: 'tidb',
        base_ref: 'main',
        base_sha: 'abcdef1234567890',
        pulls: [[number: 123, sha: '1234567890abcdef']],
    ]
}

def shouldBuildCacheKeys = {
    def script = loadProw()
    def pullRefs = refs()
    def branchRefs = pullRefs + [pulls: []]

    assert script.getCacheKey('git', pullRefs) ==
        'git/pingcap/tidb/rev-abcdef1-1234567'
    assert script.getRestoreKeys('git', pullRefs) == [
        'git/pingcap/tidb/rev-abcdef1',
        'git/pingcap/tidb/rev-',
    ]
    assert script.getCacheKey('git', branchRefs) == 'git/pingcap/tidb/rev-abcdef1'
    assert script.getRestoreKeys('git', branchRefs) == ['git/pingcap/tidb/rev-']
}

def shouldUsePublicHttpsCheckoutUrl = {
    def shCalls = []
    def script = loadProw(sh: { Map args -> shCalls << args })

    script.checkoutPublicRefs(refs(), 7, false, 'https://github.example')

    assert shCalls.size() == 1
    def checkoutScript = shCalls[0].script
    assert checkoutScript.contains(
        'git config remote.origin.url https://github.example/pingcap/tidb.git')
    assert checkoutScript.contains(
        '+refs/heads/main:refs/remotes/origin/main')
    assert checkoutScript.contains(
        '+refs/pull/123/head:refs/remotes/origin/pr/123/head')
}

def shouldSkipGithubHostKeyScanWhenCdnIsEnabled = {
    def events = []
    def script = loadProw(
        env: [GIT_CDN_ENABLED: 'true'],
        sshagent: { Map args, Closure body ->
            events << "sshagent:${args.credentials}"
            body()
        },
        sh: { Map args -> events << (args.script ?: '') },
    )

    script.checkoutPrivateRefs(refs(), 'github-sre-bot-ssh', 7, false, 'github.com')

    assert events[0] == 'sshagent:[github-sre-bot-ssh]'
    assert events[1].contains('git config remote.origin.url git@github.com:pingcap/tidb.git')
    assert events.every { !it.contains('ssh-keyscan') }
}

def shouldScopeAskPassCredentialsAndAlwaysCleanUp = {
    def events = []
    def script = loadProw(
        sh: { Map args ->
            if (args.returnStdout) {
                return '/tmp/git-askpass-test'
            }
            events << "sh:${args.script}"
        },
        libraryResource: { String path ->
            events << "resource:${path}"
            'askpass helper'
        },
        writeFile: { Map args -> events << "write:${args.file}:${args.text}" },
        usernamePassword: { Map args -> args },
        withCredentials: { List credentials, Closure body ->
            events << "credentials:${credentials[0].credentialsId}"
            body()
        },
        withEnv: { List environment, Closure body ->
            events << "env:${environment}"
            body()
        },
    )

    script.withGitAskPass('github-bot-https') { events << 'checkout' }

    assert events == [
        'resource:scripts/git_askpass.sh',
        'write:/tmp/git-askpass-test:askpass helper',
        'sh:chmod 700 \'/tmp/git-askpass-test\'',
        'credentials:github-bot-https',
        'env:[GIT_ASKPASS=/tmp/git-askpass-test, GIT_TERMINAL_PROMPT=0]',
        'checkout',
        'sh:rm -f \'/tmp/git-askpass-test\'',
    ]
}

shouldBuildCacheKeys()
shouldUsePublicHttpsCheckoutUrl()
shouldSkipGithubHostKeyScanWhenCdnIsEnabled()
shouldScopeAskPassCredentialsAndAlwaysCleanUp()
println "prow.groovy tests passed"
