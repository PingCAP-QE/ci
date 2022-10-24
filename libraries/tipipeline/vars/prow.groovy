// require plugins: 
//  - pipeline-utility-steps
def checkoutPr(prowDeckUrl, prowJobId, timeout=5, credentialsId='') {
    // get yaml from <prowDeckUrl>/prowjob?prowjob=<prow_job_id>
    def response = httpRequest "${prowDeckUrl}/prowjob?prowjob=${prowJobId}"

    // parse git refs from prowjob's yaml
    /*
       spec:
         agent: jenkins
         cluster: default
         context: debug/prow-call-jenkins
         job: prow_debug
         namespace: prow-test-pods
         prowjob_defaults:
           tenant_id: GlobalDefaultID
         refs:
           base_link: https://github.com/PingCAP-QE/ee-ops/commit/258a3136f240fa60be9ca3aa156fc1e86075cc51
           base_ref: main
           base_sha: 258a3136f240fa60be9ca3aa156fc1e86075cc51
           org: PingCAP-QE
           pulls:
             - author: wuhuizuo
               author_link: https://github.com/wuhuizuo
               commit_link: https://github.com/PingCAP-QE/ee-ops/pull/157/commits/ed162dfefe1041dfae2128e38e458cb96e8bc85c
               link: https://github.com/PingCAP-QE/ee-ops/pull/157
               number: 157
               sha: ed162dfefe1041dfae2128e38e458cb96e8bc85c
               title: "chore: test jenkins-operator job"
           repo: ee-ops
           repo_link: https://github.com/PingCAP-QE/ee-ops
    */
    final prowJob = readYaml(text: response.context)
    final refs = prowJob.spec.refs
    assert refs.pulls.size() == 1
    
    
    // parse values for git checkout.
    final org = refs.org
    final repo = refs.repo
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
                url: "https://github.com/${org}/${repo}/.git",
                credentialsId: credentialsId
            ]],
        ]
    )    
}