def checkoutRefs(refs, timeout=5, credentialsId='') {
    final remoteUrl = "https://github.com/${refs.org}/${refs.repo}.git"
    final remoteRefSpec = (
        ["+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"] + (
            (refs.pulls && refs.pulls.size() > 0) ?
            refs.pulls.collect { "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head" } :
            []
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
        git clean -fdx

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

        echo "✅ ~~~~~All done.~~~~~~"
    """
}
