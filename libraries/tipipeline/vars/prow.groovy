def checkoutRefsWithCacheLock(refs, timeout = 5, credentialsId = '', withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final lockResource = getCacheKey('git', refs)
    lock(lockResource) {
        checkoutRefsWithCache(refs, timeout, credentialsId, withSubmodule, gitBaseUrl)
    }
}

def checkoutRefsWithCache(refs, timeout = 5, credentialsId = '', withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final cacheKey = getCacheKey('git', refs)
    final restoreKeys = getRestoreKeys('git', refs)
    cache(path: "./", includes: '**/*', key: cacheKey, restoreKeys: restoreKeys) {
        retry(2) {
            checkoutRefs(refs, credentialsId, timeout, withSubmodule, gitBaseUrl)
        }
    }
}

def checkoutRefs(refs, credentialsId = '', timeout = 5, withSubmodule = false, gitBaseUrl = 'https://github.com') {
    final explicitCredentialsId = credentialsId?.trim()
    final sshCredentialsId = explicitCredentialsId ?: (
        withSubmodule ? env.GIT_SSH_CREDENTIALS_ID?.trim() : ''
    )
    final cdnEnabled = env.GIT_CDN_ENABLED?.trim()?.toBoolean()
    final httpCredentialsId = env.GIT_HTTP_CREDENTIALS_ID?.trim()

    // Keep the existing URL selection: an explicit credential means that the
    // main repository uses SSH. TKE can rewrite that URL to the CDN, while
    // GCP continues to use the SSH agent directly.
    def checkout = {
        if (explicitCredentialsId) {
            checkoutPrivateRefs(refs, explicitCredentialsId, timeout, withSubmodule, gitBaseUrl)
        } else {
            checkoutPublicRefs(refs, timeout, withSubmodule, gitBaseUrl)
        }
    }

    def checkoutWithHttpCredentials = {
        if (cdnEnabled && httpCredentialsId) {
            withGitAskPass(httpCredentialsId, checkout)
        } else {
            checkout()
        }
    }

    // A job that only passes an empty credentialsId can still have private
    // SSH submodules. Use the cluster-provided default SSH credential for
    // the checkout scope without changing the public HTTPS main URL.
    if (sshCredentialsId && !explicitCredentialsId) {
        sshagent(credentials: [sshCredentialsId]) {
            checkoutWithHttpCredentials()
        }
    } else {
        checkoutWithHttpCredentials()
    }
}

/*
 * Let Git try an anonymous HTTP request first and provide Jenkins' PAT only
 * after the server asks for credentials. The script is outside the workspace
 * because checkout cleanup and the pipeline cache operate on the workspace.
 */
def withGitAskPass(String credentialsId, Closure body) {
    // Keep the askpass script inside the shared workspace volume but outside
    // the Git worktree. Every caller clones into a workspace subdirectory via
    // dir(), so the workspace root is never touched by git clean/checkout.
    // Let writeFile create the file (it runs as the JNLP agent user); a file
    // pre-created by an sh step would be owned by the container's root user
    // and not writable by the agent, and a plain /tmp path would sit on a
    // different filesystem than the container that runs Git.
    final askPassScript = libraryResource 'scripts/git_askpass.sh'
    final tmpAskPassScript = "${env.WORKSPACE}/git-askpass-${UUID.randomUUID().toString()}"

    try {
        writeFile(file: tmpAskPassScript, text: askPassScript)
        sh label: 'Prepare Git HTTP credential helper', script: "chmod 700 '${tmpAskPassScript}'"
        withCredentials([usernamePassword(credentialsId: credentialsId, usernameVariable: 'TIPIPELINE_GIT_USERNAME', passwordVariable: 'TIPIPELINE_GIT_PASSWORD')]) {
            withEnv(["GIT_ASKPASS=${tmpAskPassScript}", 'GIT_TERMINAL_PROMPT=0']) {
                body()
            }
        }
    } finally {
        sh label: 'Remove Git HTTP credential helper', script: "rm -f '${tmpAskPassScript}'"
    }
}

def checkoutPublicRefs(refs, timeout = 5, withSubmodule = false, gitBaseUrl = 'https://github.com') {
    def remoteUrl = ""
    // Whether the git base url param is a full URL or a hostname.
    if (gitBaseUrl?.trim()?.startsWith('http')) {
        remoteUrl = "${gitBaseUrl}/${refs.org}/${refs.repo}.git"
    } else {
        remoteUrl = "https://${gitBaseUrl}/${refs.org}/${refs.repo}.git"
    }

    _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule)
}

/*
* Checkout refs from private repository.
*
* depended on plugins:
*  - ssh-agent
*/
def checkoutPrivateRefs(refs, credentialsId, timeout = 5, withSubmodule = false, gitSshHost = 'github.com') {
    def gitBaseUrl = ""
    def knownHost = gitSshHost?.trim()
    if (knownHost?.startsWith('http')) {
        knownHost = knownHost.replaceFirst('^https?://', '')
        knownHost = knownHost.replaceFirst('/.*$', '')
    }
    if (knownHost?.trim()) {
        gitBaseUrl = "git@${knownHost}"
    } else {
        knownHost = 'github.com'
        gitBaseUrl = "git@${knownHost}"
    }

    final remoteUrl = "${gitBaseUrl}:${refs.org}/${refs.repo}.git"
    sshagent(credentials: [credentialsId]) {
        // GitHub SSH URLs are rewritten to the CDN in TKE, so avoid an
        // unnecessary GitHub network call there. Keep host-key setup for
        // direct SSH and non-GitHub SSH hosts.
        if (!env.GIT_CDN_ENABLED?.trim()?.toBoolean() || knownHost != 'github.com') {
            sh label: 'Know hosts', script: """
                [ -d ~/.ssh ] || mkdir ~/.ssh && chmod 0700 ~/.ssh
                ssh-keyscan -t rsa,ecdsa,ed25519 ${knownHost} >> ~/.ssh/known_hosts
            """
        }
        _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule)
    }
}

def _checkoutRefsImpl(refs, remoteUrl, timeout, withSubmodule) {
    final remoteRefSpec = (
        ["+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"] + (
            (refs.pulls && refs.pulls.size() > 0) ? refs.pulls.collect {
                "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head"
            }: []
        )
    ).join(' ')

    final pullsSHAs = refs.pulls.collect { it.sha }.join(' ')
    sh label: 'Checkout and merge pull request(s) to target if exist', script: """#!/usr/bin/env bash
        set -e
        git --version
        git init
        git rev-parse --resolve-git-dir .git

        git config --global user.email "ti-chi-bot@ci" && git config --global user.name "TiChiBot"
        git config remote.origin.url ${remoteUrl}
        git config core.sparsecheckout true

        # reset & clean (worktree only)
        git reset --hard
        git clean -ffdx

        # Prune stale PR refs from previous cached runs.
        # These refs keep old PR commits reachable and make the cached .git grow run by run.
        git for-each-ref --format='%(refname)' 'refs/remotes/origin/pr/' | xargs -r -n 1 -I {} git update-ref -d "{}" || true

        # fetch pull requests and target branch.
        timeout ${timeout}m git fetch --force --verbose --prune --prune-tags -- ${remoteUrl} ${remoteRefSpec}

        # checkout to refs.base_sha
        git checkout -f origin/${refs.base_ref}

        echo "🚧 Checkouting to base SHA:${refs.base_sha}..."
        git checkout ${refs.base_sha}
        echo "✅ Checked. 🎉"

        echo "🧾 HEAD info:"
        git rev-parse HEAD^{commit}
        git log -n 3 --oneline

        # merge pull requests to base if exist.
        if [ -n "${pullsSHAs}" ]; then
            echo "🚧 Pre-merge heads of pull requests to base SHA: ${refs.base_sha} ..."
            git merge --no-edit ${pullsSHAs}

            echo "🧾 Pre-merged result:"
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline

            echo "✅ Pre merged 🎉"
        fi

        git clean -ffdx
        if [ "${withSubmodule}" == "true" ]; then
            echo "📁 update submodules ..."
            GIT_SSH_COMMAND="ssh -o StrictHostKeyChecking=no" git submodule update --init --recursive
            echo "✅ update submodules done"
        fi

        # Git maintenance to keep cached workspaces bounded in size.
        # Also run gc in submodules if present (workspace cache includes them).
        (
            git reflog expire --expire=now --all || true
            git gc --prune=now || true
            if [ -f .gitmodules ]; then
                git submodule foreach --recursive '
                    git reflog expire --expire=now --all || true
                    git gc --prune=now || true
                ' || true
            fi
        ) >/dev/null 2>&1 || true

        echo "✅ ~~~~~All done.~~~~~~"
    """
}

// get uniq cache save key by refs.
def getCacheKey(prefixFolder, refs, part = '') {
    final prefix = ([prefixFolder, refs.org, refs.repo, part, 'rev-'] - '').join('/')
    if (refs.pulls && refs.pulls.size() > 0) {
        // <base>-<p1>_<p2>_...<pN>
        return prefix + [refs.base_sha[0..<7], refs.pulls.collect { it.sha[0..<7] }.join('_')].join('-')
    } else {
        return prefix + refs.base_sha[0..<7]
    }
}

// get cache restory keys by refs.
def getRestoreKeys(prefixFolder, refs, part = '') {
    final prefix = ([prefixFolder, refs.org, refs.repo, part, 'rev-'] - '').join('/')
    if (refs.pulls && refs.pulls.size() > 0) {
        return [prefix + refs.base_sha[0..<7], prefix]
    } else {
        return [prefix]
    }
}

def uploadCoverageToCodecov(refs, flags = "", file = "",  bazelLCov = false, bazelOptions = "") {
    // Skip for batch build.
    if (refs.pulls && refs.pulls.size() > 1) {
        return
    }

    final codecovGitOptions = (refs.pulls ?
         "--branch origin/pr/${refs.pulls[0].number} --sha ${refs.pulls[0].sha} --pr ${refs.pulls[0].number}" :
         "--branch origin/${refs.base_ref} --sha ${refs.base_sha}"
    )

    sh label: "upload coverage to codecov", script: """#!/usr/bin/env bash
        coverageFile=${file}
        if [ "${bazelLCov}" == "true" ]; then
            coverageFile="bazel_coverage.xml"
            bazelCoverageData=`bazel ${bazelOptions} info output_path`/_coverage/_coverage_report.dat
            if [ -f \$bazelCoverageData ]; then
                echo "Convert bazel LCOV data to cobertura XML..."
                wget https://raw.github.com/eriwen/lcov-to-cobertura-xml/master/lcov_cobertura/lcov_cobertura.py
                python3 lcov_cobertura.py \$bazelCoverageData --output=\${coverageFile}
                echo "✅ Converted bazel LCOV data to cobertura XML."
            else
                echo "🏃 Not found bazel LCOV data."
            fi
        fi

        if [ -f \$coverageFile ]; then
            # Prefer the codecov binary bundled in the CI base image; download from
            # the public uploader.codecov.io only when it is not available.
            if command -v codecov >/dev/null 2>&1; then
                codecov_bin="codecov"
            else
                echo "INFO: codecov not found in image; downloading from uploader.codecov.io."
                curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 5 --max-time 120 \
                    -o codecov "https://uploader.codecov.io/v0.8.0/linux/codecov"
                chmod +x codecov
                codecov_bin="./codecov"
            fi
            \${codecov_bin} --rootDir . --flags ${flags} --file \${coverageFile} ${codecovGitOptions}
        fi
    """
}

// send test case run report to cloudevents server
def sendTestCaseRunReport(repo, branch, dataFile = 'bazel-go-test-problem-cases.json') {
    sh label: 'Send event to cloudevents server', script: """timeout 10 \
        curl --verbose --request POST --url http://cloudevents-server.cs.svc/events \
        --header "ce-id: \$(uuidgen)" \
        --header "ce-source: \${JENKINS_URL}" \
        --header 'ce-type: test-case-run-report' \
        --header 'ce-repo: ${repo}' \
        --header 'ce-branch: ${branch}' \
        --header "ce-buildurl: \${BUILD_URL}" \
        --header 'ce-specversion: 1.0' \
        --header 'content-type: application/json; charset=UTF-8' \
        --data @${dataFile} || true
    """
}

// print PR info on pipeline run description.
def setPRDescription(refs) {
    try {
        if (refs.pulls && refs.pulls.size() > 0) {
            currentBuild.description = "PR #${refs.pulls[0].number}: ${refs.pulls[0].title} ${refs.pulls[0].link}"
        } else {
            println("No pull request information available.")
        }
    } catch (Exception e) {
        println("Failed to set PR description: ${e.message}. Please ignore it. 🤷")
    }
}
