echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def COPR_TEST_BRANCH = "master"

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

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

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release = "release"

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

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
        it.startsWith('release-') ? it.minus('release-') : it 
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
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}

if (isNeedGo1160) {
    println "This build use go1.16 because ghprTargetBranch=master"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = "test_heavy_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Build') {
        node("build") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            // checkout tikv
            // dir("/home/jenkins/agent/git/tikv") {
            //     if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
            //         deleteDir()
            //     }
            //     checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
            // }

            def filepath = "builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
            def refspath = "refs/pingcap/tikv/pr/${ghprbPullId}/sha1"

            dir("tikv") {
                container("rust") {
                    deleteDir()
                    timeout(30) {
                        sh """
                        set +e
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        set -e
                        (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        """
                    }
                }
            }

            stash includes: "tikv/bin/**", name: "tikv"
            deleteDir()
        }
    }


    node("${GO_TEST_SLAVE}") {
        def ws = pwd()
        
        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

        stage('Prepare') {
            dir("copr-test") {
                timeout(30) {
                    checkout(changelog: false, poll: false, scm: [
                        $class: "GitSCM",
                        branches: [ [ name: COPR_TEST_BRANCH ] ],
                        userRemoteConfigs: [ [ url: 'https://github.com/tikv/copr-test.git',
                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*' ] ],
                        extensions: [ [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'] ],
                    ])
                }
            }
            container("golang") {
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
                    def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }
            }
            
            dir("tikv") { deleteDir() }

            unstash "tikv"
            sh "find tikv"
        }

        stage('Integration Push Down Test') {
            def pd_bin = "${ws}/pd/bin/pd-server"
            def tikv_bin = "${ws}/tikv/bin/tikv-server"
            def tidb_src_dir = "${ws}/tidb"
            dir('copr-test') {
                container('golang') {
                    try {
                        sh """
                        pd_bin=${pd_bin} tikv_bin=${tikv_bin} tidb_src_dir=${tidb_src_dir} make push-down-test
                        """
                    } catch (Exception e) {
                        def build_dir = "push-down-test/build"
                        sh "cat ${build_dir}/tidb_no_push_down.log || true"
                        sh "cat ${build_dir}/tidb_with_push_down.log || true"
                        sh "cat ${build_dir}/pd_with_push_down.log || true"
                        sh "cat ${build_dir}/tikv_with_push_down.log || true"
        
                        sh "echo Test failed. Check out logs above."
                        
                        throw e;
                    }
                }
            }
        }

    }
    currentBuild.result = "SUCCESS"
} 

catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        slackSend channel: '#push-down-expr-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
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
                    string(name: 'TIKV_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tikv/integration-copr-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
