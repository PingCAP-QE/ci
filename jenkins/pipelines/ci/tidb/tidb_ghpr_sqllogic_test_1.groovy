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

// parse tidb_test branch
def TIDB_TEST_BRANCH = ghprbTargetBranch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

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

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"

try {
    timestamps {
        stage("Pre-check") {
            if (!params.force) {
                node("${GO_BUILD_SLAVE}") {
                    container("golang") {
                        notRun = sh(returnStatus: true, script: """
				    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
				    """)
                    }
                }
            }

            if (notRun == 0) {
                println "the ${ghprbActualCommit} has been tested"
                throw new RuntimeException("hasBeenTested")
            }
        }

        def buildSlave = "${GO_BUILD_SLAVE}"
        def testSlave = "${GO_TEST_SLAVE}"

        stage('Prepare') {
            node(buildSlave) {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                def ws = pwd()
                deleteDir()

                dir("go/src/github.com/pingcap/tidb") {
                    container("golang") {
                        timeout(10) {
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        # use tidb-server with ADMIN_CHECK as default
                        mkdir -p ${ws}/go/src/github.com/pingcap/tidb-test/sqllogic_test/
                        mv bin/tidb-server-check ${ws}/go/src/github.com/pingcap/tidb-test/sqllogic_test/tidb-server
                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap/tidb-test") {
                    container("golang") {
                        timeout(5) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                        """
                            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        cd sqllogic_test && ./build.sh
                        """
                        }
                    }
                }

                stash includes: "go/src/github.com/pingcap/tidb-test/sqllogic_test/**", name: "tidb-test"
                deleteDir()
            }
        }

        stage('SQL Logic Test') {
            def run = { sqllogictest, parallelism, enable_cache ->
                node(testSlave) {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    deleteDir()
                    unstash 'tidb-test'

                    dir("go/src/github.com/pingcap/tidb-test/sqllogic_test") {
                        container("golang") {
                            timeout(10) {
                                try {
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest} \
                                    TIDB_PARALLELISM=${parallelism} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    """
                                } catch (err) {
                                    throw err
                                }finally{
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    """
                                }
                            }
                        }
                    }
                }
            }

            def run_two = { sqllogictest_1, parallelism_1, sqllogictest_2, parallelism_2, enable_cache ->
                node(testSlave) {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    deleteDir()
                    unstash 'tidb-test'

                    dir("go/src/github.com/pingcap/tidb-test/sqllogic_test") {
                        container("golang") {
                            timeout(10) {
                                try{
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest_1} \
                                    TIDB_PARALLELISM=${parallelism_1} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    set -ex
                                    
                                    sleep 30
        
                                    SQLLOGIC_TEST_PATH=${sqllogictest_2} \
                                    TIDB_PARALLELISM=${parallelism_2} \
                                    TIDB_SERVER_PATH=`pwd`/tidb-server \
                                    CACHE_ENABLED=${enable_cache} \
                                    ./test.sh
                                    """
                                }catch(err){
                                    throw err
                                }finally{
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    """
                                }

                            }
                        }
                    }
                }
            }

            def tests = [:]

            tests["SQLLogic Random Aggregates_n1 Test"] = {
                run('/git/sqllogictest/test/random/aggregates_n1', 8, 0)
            }

            tests["SQLLogic Random Aggregates_n2 Test"] = {
                run('/git/sqllogictest/test/random/aggregates_n2', 8, 0)
            }

            tests["SQLLogic Random Expr Test"] = {
                run('/git/sqllogictest/test/random/expr', 8, 0)
            }

            tests["SQLLogic Random Select_n1 Test"] = {
                run('/git/sqllogictest/test/random/select_n1', 8, 0)
            }

            tests["SQLLogic Random Select_n2 Test"] = {
                run('/git/sqllogictest/test/random/select_n2', 8, 0)
            }

            tests["SQLLogic Select Groupby Test"] = {
                run_two('/git/sqllogictest/test/select', 8, '/git/sqllogictest/test/random/groupby', 8, 0)
            }

            tests["SQLLogic Index Between 1 10 Test"] = {
                run_two('/git/sqllogictest/test/index/between/1', 10, '/git/sqllogictest/test/index/between/10', 8, 0)
            }

            tests["SQLLogic Index Between 100 Test"] = {
                run('/git/sqllogictest/test/index/between/100', 8, 0)
            }

            tests["SQLLogic Index Between 1000 Test"] = {
                run('/git/sqllogictest/test/index/between/1000', 8, 0)
            }

            tests["SQLLogic Index commute 10 Test"] = {
                run('/git/sqllogictest/test/index/commute/10', 8, 0)
            }

            tests["SQLLogic Index commute 100 Test"] = {
                run('/git/sqllogictest/test/index/commute/100', 8, 0)
            }

            tests["SQLLogic Index commute 1000_n1 Test"] = {
                run('/git/sqllogictest/test/index/commute/1000_n1', 8, 0)
            }

            tests["SQLLogic Index commute 1000_n2 Test"] = {
                run('/git/sqllogictest/test/index/commute/1000_n2', 8, 0)
            }

            if (ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-3") || ghprbTargetBranch.startsWith("release-4")) {
                tests["SQLLogic Random Aggregates_n1 Cache Test"] = {
                    run('/git/sqllogictest/test/random/aggregates_n1', 8, 1)
                }

                tests["SQLLogic Random Aggregates_n2 Cache Test"] = {
                    run('/git/sqllogictest/test/random/aggregates_n2', 8, 1)
                }

                tests["SQLLogic Random Expr Cache Test"] = {
                    run('/git/sqllogictest/test/random/expr', 8, 1)
                }

                tests["SQLLogic Random Select_n1 Cache Test"] = {
                    run('/git/sqllogictest/test/random/select_n1', 8, 1)
                }

                tests["SQLLogic Random Select_n2 Cache Test"] = {
                    run('/git/sqllogictest/test/random/select_n2', 8, 1)
                }

                tests["SQLLogic Select Groupby Cache Test"] = {
                    run_two('/git/sqllogictest/test/select', 8, '/git/sqllogictest/test/random/groupby', 8, 1)
                }

                tests["SQLLogic Index Between 1 10 Cache Test"] = {
                    run_two('/git/sqllogictest/test/index/between/1', 10, '/git/sqllogictest/test/index/between/10', 8, 1)
                }

                tests["SQLLogic Index Between 100 Cache Test"] = {
                    run('/git/sqllogictest/test/index/between/100', 8, 1)
                }

                tests["SQLLogic Index Between 1000 Cache Test"] = {
                    run('/git/sqllogictest/test/index/between/1000', 8, 1)
                }

                tests["SQLLogic Index commute 10 Cache Test"] = {
                    run('/git/sqllogictest/test/index/commute/10', 8, 1)
                }

                tests["SQLLogic Index commute 100 Cache Test"] = {
                    run('/git/sqllogictest/test/index/commute/100', 8, 1)
                }

                tests["SQLLogic Index commute 1000_n1 Cache Test"] = {
                    run('/git/sqllogictest/test/index/commute/1000_n1', 8, 1)
                }

                tests["SQLLogic Index commute 1000_n2 Cache Test"] = {
                    run('/git/sqllogictest/test/index/commute/1000_n2', 8, 1)
                }
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
        node("${GO_BUILD_SLAVE}") {
            container("golang") {
                sh """
		    echo "done" > done
		    curl -F ci_check/tidb_ghpr_integration_sqllogic_test_1/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
            }
        }
    }
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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

stage("upload status") {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/sqllogic-test-1'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "SQL Logic Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (duration >= 3 && ghprbTargetBranch == "master" && currentBuild.result == "SUCCESS") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}