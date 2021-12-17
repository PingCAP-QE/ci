def notRun = 1

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIDB_BRANCH = ghprbTargetBranch
// def PD_BRANCH = ghprbTargetBranch
def PD_BRANCH = "master"
// def COPR_TEST_BRANCH = ghprbTargetBranch
def COPR_TEST_BRANCH = "master"
def TIKV_BRANCH = ghprbTargetBranch

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse copr-test branch
def m3 = ghprbCommentBody =~ /copr[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    COPR_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null
println "COPR_TEST_BRANCH=${COPR_TEST_BRANCH}"

// def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release = "release"

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it 
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc", "ft-data-inconsistency", "br-stream"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}

if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
all_task_result = []

try {
    stage("Pre-check"){
        if (!params.force){
            node("${GO_BUILD_SLAVE}"){
                container("golang"){
                    notRun = sh(returnStatus: true, script: """
				    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
				    """)
                }
            }
        }

        if (notRun == 0){
            println "the ${ghprbActualCommit} has been tested"
            throw new RuntimeException("hasBeenTested")
        }
    }

    node("${GO_TEST_SLAVE}") {
        def ws = pwd()

        stage('Prepare') {
            dir("copr-test") {
                timeout(30) {
                    checkout(changelog: false, poll: false, scm: [
                            $class: "GitSCM",
                            branches: [ [ name: COPR_TEST_BRANCH ] ],
                            userRemoteConfigs: [
                                    [
                                            url: 'https://github.com/tikv/copr-test.git',
                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*',
                                    ]
                            ],
                            extensions: [
                                    [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'],
                                    [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
                            ],
                    ])
                }
            }
            container("golang") {
                dir("tikv"){
                    deleteDir()

                    timeout(30) {
                        def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        sh"""
                        while ! curl --output /dev/null --silent --head --fail ${tikv_refs}; do sleep 5; done
                        """
                        def tikv_sha1 =  sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                        def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        curl ${tikv_url} | tar xz
                        """
                    }
                }
                dir("pd") {
                    deleteDir()
                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz
                        """
                    }
                }
                dir("tidb") {
                    deleteDir()
                    // def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                    def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }
            }

            // dir("tikv") { deleteDir() }
            // unstash "tikv"
            // sh "find tikv"
        }

        stage('Integration Push Down Test') {
            try {
                def pd_bin = "${ws}/pd/bin/pd-server"
                def tikv_bin = "${ws}/tikv/bin/tikv-server"
                def tidb_src_dir = "${ws}/tidb"
                dir('copr-test') {
                    container('golang') {
                        try{
                            timeout(30){
                                sh """
                                pd_bin=${pd_bin} tikv_bin=${tikv_bin} tidb_src_dir=${tidb_src_dir} make push-down-test
                                """
                            }
                        }catch (Exception e) {
                            def build_dir = "push-down-test/build"
                            sh "cat ${build_dir}/tidb_no_push_down.log || true"
                            sh "cat ${build_dir}/tidb_with_push_down.log || true"
                            sh "cat ${build_dir}/tikv_with_push_down.log || true"
                            sh "echo Test failed. Check out logs above."
                            throw e;
                        }

                    }
                }

                all_task_result << ["name": "Integration Push Down Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "Integration Push Down Test", "status": "failed", "error": err.message]
                throw err
            }
        }
    }
    currentBuild.result = "SUCCESS"
}
catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
finally {
    stage("task summary") {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            println "all_results: ${json}"
            currentBuild.description = "${json}"
        }
    }
}

if (params.containsKey("triggered_by_upstream_ci")) {
    stage("update commit status") {
        node("master") {
            if (currentBuild.result == "ABORTED") {
                PARAM_DESCRIPTION = 'Jenkins job aborted'
                // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                PARAM_STATUS = 'error'
            } else if (currentBuild.result == "FAILURE") {
                PARAM_DESCRIPTION = 'Jenkins job failed'
                PARAM_STATUS = 'failure'
            } else if (currentBuild.result == "SUCCESS") {
                PARAM_DESCRIPTION = 'Jenkins job success'
                PARAM_STATUS = 'success'
            } else {
                PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                PARAM_STATUS = 'error'
            }
            def default_params = [
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-copr-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}