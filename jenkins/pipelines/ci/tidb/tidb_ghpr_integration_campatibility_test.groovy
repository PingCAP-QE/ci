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


def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch
def TIDB_OLD_BRANCH = ghprbTargetBranch

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
    TIDB_OLD_BRANCH = params.getOrDefault('release_test__tidb_old_commit', ghprbActualCommit)
}

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

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// parse tidb branch
def m4 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIDB_OLD_BRANCH = "${m4[0][1]}"
}
m4 = null
println "TIDB_OLD_BRANCH=${TIDB_OLD_BRANCH}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], ghprbTargetBranch)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

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

    def buildSlave = "${GO_BUILD_SLAVE}"
    def testSlave = "${GO_TEST_SLAVE}"

    stage('Prepare') {

        node(buildSlave) {
            def ws = pwd()
            deleteDir()

            container("golang") {

                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }

                dir("go/src/github.com/pingcap/tidb-test") {
                    timeout(30) {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                        """
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        cd compatible_test && ./build.sh
                        """
                    }
                }
            }

            stash includes: "go/src/github.com/pingcap/tidb-test/compatible_test/**", name: "compatible_test"
            deleteDir()
        }
    }

    stage('Integration Compatibility Test') {
        node(testSlave) {
            def ws = pwd()
            deleteDir()
            unstash 'compatible_test'

            dir("go/src/github.com/pingcap/tidb-test/compatible_test") {
                container("golang") {
                    def tidb_old_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_OLD_BRANCH}/sha1"
                    def tidb_old_sha1 = sh(returnStdout: true, script: "curl ${tidb_old_refs}").trim()
                    sh """
                    time curl ${tidb_old_refs}
                    """
                    def tidb_old_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_old_sha1}/centos7/tidb-server.tar.gz"

                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        curl ${tikv_url} | tar xz

                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz

                        mkdir -p ./tidb-old-src
                        echo ${tidb_old_url}
                        echo ${tidb_old_refs}
                        while ! curl --output /dev/null --silent --head --fail ${tidb_old_url}; do sleep 15; done
                        curl ${tidb_old_url} | tar xz -C ./tidb-old-src

                        mkdir -p ./tidb-src
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz -C ./tidb-src

                        mv tidb-old-src/bin/tidb-server bin/tidb-server-old
                        mv tidb-src/bin/tidb-server ./bin/tidb-server
                        """
                    }

                    timeout(10) {
                        try {
                            sh """
                            set +e 
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            export log_level=debug
                            TIKV_PATH=./bin/tikv-server \
                            TIDB_PATH=./bin/tidb-server \
                            PD_PATH=./bin/pd-server \
                            UPGRADE_PART=tidb \
                            NEW_BINARY=./bin/tidb-server \
                            OLD_BINARY=./bin/tidb-server-old \
                            ./test.sh 2>&1
                            """
                        } catch (err) {
                            sh "cat tidb*.log"
                            throw err
                        } finally {
                            sh """
                            set +e 
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
            deleteDir()
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
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Integration Compatibility Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (duration >= 3 && ghprbTargetBranch == "master" && currentBuild.result == "SUCCESS") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master") {
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.25:36000/api/v1/ci/job/sync || true"""
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-compatibility-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}