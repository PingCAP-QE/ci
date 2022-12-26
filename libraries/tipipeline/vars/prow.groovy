// require plugins: 
//  - pipeline-utility-steps
def getPrRefs(prowDeckUrl, prowJobId) {
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
    final refs = prowJob.spec.refs
    assert refs.pulls.size() == 1
    
    return refs
}


def checkoutPr(prowDeckUrl, prowJobId, timeout=5, credentialsId='') {
    final refs = getPrRefs(prowDeckUrl, prowJobId)
    
    // parse values for git checkout.
    final pullId = refs.pulls[0].number
    final pullCommitSha = refs.pulls[0].sha

    checkout(
        changelog: false,
        poll: false,
        scm: [
            $class: 'GitSCM', 
            branches: [[name: pullCommitSha]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'PruneStaleBranch'],
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', timeout: timeout],
            ],
            submoduleCfg: [],
            userRemoteConfigs: [[
                refspec: "+refs/pull/${pullId}/*:refs/remotes/origin/pr/${pullId}/*",
                url: "https://github.com/${refs.org}/${refs.repo}.git",
                credentialsId: credentialsId
            ]],
        ]
    )    
}
