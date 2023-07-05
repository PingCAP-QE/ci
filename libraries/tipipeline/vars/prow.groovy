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

        # reset & clean
        git reset --hard
        git clean -ffdx

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
            ssh-keyscan -t rsa,dsa ${gitSshHost} >> ~/.ssh/known_hosts
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

            # reset & clean
            git reset --hard
            git clean -ffdx

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
