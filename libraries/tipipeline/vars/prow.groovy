def checkoutRefsWithCacheLock(refs, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule = false) {
    final lockResource = getCacheKey('git', refs)
    lock(lockResource) {
        checkoutRefsWithCache(refs, timeout, credentialsId, gitBaseUrl, withSubmodule)
    }
}

def checkoutPrivateRefsWithCacheLock(refs, credentialsId, timeout = 5, gitSshHost = 'github.com', withSubmodule = false) {
    final lockResource = getCacheKey('git', refs)
    lock(lockResource) {
        checkoutPrivateRefsWithCache(refs, credentialsId, timeout, gitSshHost, withSubmodule)
    }
}

def checkoutRefsWithCache(refs, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule = false) {
    final cacheKey = getCacheKey('git', refs)
    final restoreKeys = getRestoreKeys('git', refs)
    cache(path: "./", includes: '**/*', key: cacheKey, restoreKeys: restoreKeys) {
        retry(2) {
            checkoutRefs(refs, timeout, credentialsId, gitBaseUrl, withSubmodule)
        }
    }
}

def checkoutPrivateRefsWithCache(refs, credentialsId, timeout = 5, gitSshHost = 'github.com', withSubmodule = false) {
    final cacheKey = getCacheKey('git', refs)
    final restoreKeys = getRestoreKeys('git', refs)
    cache(path: "./", includes: '**/*', key: cacheKey, restoreKeys: restoreKeys) {
        retry(2) {
            checkoutPrivateRefs(refs, credentialsId, timeout, gitSshHost, withSubmodule)
        }
    }
}

def checkoutRefs(refs, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule = false) {
    final remoteUrl = "${gitBaseUrl}/${refs.org}/${refs.repo}.git"
    final remoteRefSpec = (
        ["+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"] + (
            (refs.pulls && refs.pulls.size() > 0) ? refs.pulls.collect {
                "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head"
            }: []
        )
    ).join(' ')

    final pullsSHAs = refs.pulls.collect { it.sha }.join(' ')
    // checkout base.
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

/*
* Checkout refs from private repository.
*
* depended on plugins:
*  - ssh-agent
*/
def checkoutPrivateRefs(refs, credentialsId, timeout = 5, gitSshHost = 'github.com', withSubmodule = false) {
    final remoteUrl = "git@${gitSshHost}:${refs.org}/${refs.repo}.git"
    final remoteRefSpec = (
        ["+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"] + (
            (refs.pulls && refs.pulls.size() > 0) ? refs.pulls.collect {
                "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head"
            }: []
        )
    ).join(' ')

    final pullsSHAs = refs.pulls.collect { it.sha }.join(' ')

    sshagent(credentials: [credentialsId]) {
        sh label: 'Know hosts', script: """
            [ -d ~/.ssh ] || mkdir ~/.ssh && chmod 0700 ~/.ssh
            ssh-keyscan -t rsa,ecdsa,ed25519 ${gitSshHost} >> ~/.ssh/known_hosts
        """
        // checkout base.
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
            wget -q -O codecov http://fileserver.pingcap.net/download/cicd/tools/codecov-v0.5.0
            chmod +x codecov
            ./codecov --rootDir . --flags ${flags} --file \${coverageFile} ${codecovGitOptions}
        fi
    """
}

// send test case run report to cloudevents server
def sendTestCaseRunReport(repo, branch, dataFile = 'bazel-go-test-problem-cases.json') {
    sh label: 'Send event to cloudevents server', script: """timeout 10 \
        curl --verbose --request POST --url https://internal2-do.pingcap.net/cloudevents-server/events \
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
