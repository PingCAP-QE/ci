// require plugins: 
//  - pipeline-utility-steps
def getJobSpec(prowDeckUrl, prowJobId) {
    // get yaml from <prowDeckUrl>/prowjob?prowjob=<prow_job_id>
    def response = httpRequest "${prowDeckUrl}/prowjob?prowjob=${prowJobId}"

    // parse git refs from prowjob's yaml, format:    
    // spec:
    //   refs:
    //     base_link: <string>
    //     base_ref: <base_branch_name>
    //     base_sha: <base_branch_sha>
    //     org: <string>
    //     repo: <string>
    //     repo_link: <string>
    //     pulls:
    //       - author: <string>
    //         author_link: <string>
    //         commit_link: <string>
    //         link: <string>
    //         number: <pr number>
    //         sha: <head merge commit sha>
    //         title: <pr title>
    final prowJob = readYaml(text: response.content)
    return prowJob.spec
}

// require plugins: 
//  - pipeline-utility-steps
def getJobRefs(prowDeckUrl, prowJobId) {
    return getJobSpec(prowDeckUrl, prowJobId).refs
}

def checkoutRefs(refs, timeout=5, credentialsId='') {
    final remoteUrl = "https://github.com/${refs.org}/${refs.repo}.git"
    final remoteRefSpec = "+refs/heads/${refs.base_ref}:refs/remotes/origin/${refs.base_ref}"
    final branches = [[name: refs.base_sha]]

    // for pull requests.
    if (refs.pulls && refs.pulls.size() > 0) {
        // +refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*
        final remotePullRefSpecs = refs.pulls.collect { 
            "+refs/pull/${it.number}/head:refs/remotes/origin/pr/${it.number}/head" }
        remoteRefSpec = ([remoteRefSpec] + remotePullRefSpecs).join(' ')
        branches = refs.pulls.collect { [name: it.sha] }
    }

    // checkout base 
    sh """#!/usr/bin/env bash

        set -ex

        git --version
        git init

        git rev-parse --resolve-git-dir .git
        git config remote.origin.url ${remoteUrl}

        # reset & clean
        git reset --hard
        git clean -fdx

        # fetch pull requests and target branch.
        git fetch --force --progress --prune -- ${remoteUrl} ${remoteRefSpec}

        # pre merge
        git config core.sparsecheckout true

        git rev-parse ${refs.base_sha}^{commit}
        git rev-parse origin/${refs.base_ref}^{commit}
        git checkout -f origin/${refs.base_ref}
    """

    // checkout pulls and merge them.
    if (refs.pulls && refs.pulls.size() > 0) {
        sh 'git config --global user.email "ti-chi-bot@ci" && git config --global user.name "TiChiBot"'
        refs.pulls.each{
            sh "git merge --ff --no-edit ${it.sha} && git rev-parse HEAD^{commit}"
        }
    }
}
