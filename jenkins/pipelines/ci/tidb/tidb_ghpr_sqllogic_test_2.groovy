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

m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest",
]
POD_LABEL_MAP = [
    "go1.13": "tidb-ghpr-common-test-go1130-${BUILD_NUMBER}",
    "go1.16": "tidb-ghpr-common-test-go1160-${BUILD_NUMBER}",
    "go1.18": "tidb-ghpr-common-test-go1180-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy'
    sh "curl -O --retry 3 --retry-delay 5 --retry-connrefused --fail ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}
POD_NAMESPACE = "jenkins-ticdc"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
def waitBuildDone = 0

def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],  
                    )
            ],
            volumes: [
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

all_task_result = []

try {
    stage("Pre-check") {
        if (!params.force) {
            node("lightweight_pod") {
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

    stage('Prepare') {
        run_with_pod {
            def ws = pwd()
            deleteDir()

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    timeout(10) {
                        def waitBuildDoneStartTimeMillis = System.currentTimeMillis()
                        sh """
                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                    """
                        waitBuildDone = System.currentTimeMillis() - waitBuildDoneStartTimeMillis

                        sh """
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
                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                    sh """
                while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                """
                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                    timeout(10) {
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
            run_with_pod {
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
                deleteDir()
            }
        }

        def run_two = { sqllogictest_1, parallelism_1, sqllogictest_2, parallelism_2, enable_cache ->
            run_with_pod {
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
                deleteDir()
            }
        }

        def tests = [:]

        tests["SQLLogic Index delete 1 Test"] = {
            try {
                run('/git/sqllogictest/test/index/delete/1', 8, 0)
                all_task_result << ["name": "SQLLogic Index delete 1 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index delete 1 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index delete 10 Test"] = {
            try {
                run('/git/sqllogictest/test/index/delete/10', 8, 0)
                all_task_result << ["name": "SQLLogic Index delete 10 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index delete 10 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index delete 100 Test"] = {
            try {
                run('/git/sqllogictest/test/index/delete/100', 8, 0)
                all_task_result << ["name": "SQLLogic Index delete 100 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index delete 100 Test", "status": "failed", "error": err.message]
                throw err
            } 
        }

        tests["SQLLogic Index delete 1000 Test"] = {
            try {
                run('/git/sqllogictest/test/index/delete/1000', 8, 0)
                all_task_result << ["name": "SQLLogic Index delete 1000 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index delete 1000 Test", "status": "failed", "error": err.message]
                throw err
            } 
        }

        tests["SQLLogic Index delete 10000 Test"] = {
            try {
                run('/git/sqllogictest/test/index/delete/10000', 8, 0)
                all_task_result << ["name": "SQLLogic Index delete 10000 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index delete 10000 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index in 10 100 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/in/10', 8, '/git/sqllogictest/test/index/in/100', 8, 0)
                all_task_result << ["name": "SQLLogic Index in 10 100 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index in 10 100 Test", "status": "failed", "error": err.message]
                throw err
            } 
        }

        tests["SQLLogic Index in 1000_n1 Test"] = {
            try {
                run('/git/sqllogictest/test/index/in/1000_n1', 8, 0)
                all_task_result << ["name": "SQLLogic Index in 1000_n1 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index in 1000_n1 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index in 1000_n2 Test"] = {
            try {
                run('/git/sqllogictest/test/index/in/1000_n2', 8, 0)
                all_task_result << ["name": "SQLLogic Index in 1000_n2 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index in 1000_n2 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index orderby 10 100 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/orderby/10', 10, '/git/sqllogictest/test/index/orderby/100', 8, 0)
                all_task_result << ["name": "SQLLogic Index orderby 10 100 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index orderby 10 100 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index orderby 1000 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/orderby/1000_n1', 8, '/git/sqllogictest/test/index/orderby/1000_n2', 8, 0)
                all_task_result << ["name": "SQLLogic Index orderby 1000 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index orderby 1000 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index orderby_nosort 10 100 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/orderby_nosort/10', 10, '/git/sqllogictest/test/index/orderby_nosort/100', 8, 0)
                all_task_result << ["name": "SQLLogic Index orderby_nosort 10 100 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index orderby_nosort 10 100 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index orderby_nosort 1000 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/orderby_nosort/1000_n1', 8, '/git/sqllogictest/test/index/orderby_nosort/1000_n2', 8, 0)
                all_task_result << ["name": "SQLLogic Index orderby_nosort 1000 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index orderby_nosort 1000 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index random 10 100 Test"] = {
            try {
                run_two('/git/sqllogictest/test/index/random/10', 8, '/git/sqllogictest/test/index/random/100', 8, 0)
                all_task_result << ["name": "SQLLogic Index random 10 100 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index random 10 100 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["SQLLogic Index random 1000 Test"] = {
            try {
                run('/git/sqllogictest/test/index/random/1000', 8, 0)
                all_task_result << ["name": "SQLLogic Index random 1000 Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "SQLLogic Index random 1000 Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        if (ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-")) {
            tests["SQLLogic Index delete 1 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/delete/1', 8, 1)
                    all_task_result << ["name": "SQLLogic Index delete 1 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index delete 1 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index delete 10 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/delete/10', 8, 1)
                    all_task_result << ["name": "SQLLogic Index delete 10 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index delete 10 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index delete 100 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/delete/100', 8, 1)
                    all_task_result << ["name": "SQLLogic Index delete 100 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index delete 100 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index delete 1000 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/delete/1000', 8, 1)
                    all_task_result << ["name": "SQLLogic Index delete 1000 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index delete 1000 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index delete 10000 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/delete/10000', 8, 1)
                    all_task_result << ["name": "SQLLogic Index delete 10000 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index delete 10000 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index in 10 100 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/in/10', 8, '/git/sqllogictest/test/index/in/100', 8, 1)
                    all_task_result << ["name": "SQLLogic Index in 10 100 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index in 10 100 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index in 1000_n1 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/in/1000_n1', 8, 1)
                    all_task_result << ["name": "SQLLogic Index in 1000_n1 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index in 1000_n1 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index in 1000_n2 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/in/1000_n2', 8, 1)
                    all_task_result << ["name": "SQLLogic Index in 1000_n2 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index in 1000_n2 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index orderby 10 100 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/orderby/10', 8, '/git/sqllogictest/test/index/orderby/100', 8, 1)
                    all_task_result << ["name": "SQLLogic Index orderby 10 100 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index orderby 10 100 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index orderby 1000 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/orderby/1000_n1', 8, '/git/sqllogictest/test/index/orderby/1000_n2', 8, 1)
                    all_task_result << ["name": "SQLLogic Index orderby 1000 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index orderby 1000 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index orderby_nosort 10 100 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/orderby_nosort/10', 10, '/git/sqllogictest/test/index/orderby_nosort/100', 8, 1)
                    all_task_result << ["name": "SQLLogic Index orderby_nosort 10 100 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index orderby_nosort 10 100 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index orderby_nosort 1000 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/orderby_nosort/1000_n1', 8, '/git/sqllogictest/test/index/orderby_nosort/1000_n2', 8, 1)
                    all_task_result << ["name": "SQLLogic Index orderby_nosort 1000 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index orderby_nosort 1000 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index random 10 100 Cache Test"] = {
                try {
                    run_two('/git/sqllogictest/test/index/random/10', 8, '/git/sqllogictest/test/index/random/100', 8, 1)
                    all_task_result << ["name": "SQLLogic Index random 10 100 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index random 10 100 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["SQLLogic Index random 1000 Cache Test"] = {
                try {
                    run('/git/sqllogictest/test/index/random/1000', 8, 1)
                    all_task_result << ["name": "SQLLogic Index random 1000 Cache Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "SQLLogic Index random 1000 Cache Test", "status": "failed", "error": err.message]
                    throw err
                }
            }
        }

        parallel tests
    }
    
    currentBuild.result = "SUCCESS"
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
} finally {
    stage("task summary") {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            println "all_results: ${json}"
            currentBuild.description = "${json}"
        }
    }
}



if (params.containsKey("triggered_by_upstream_ci")  && params.get("triggered_by_upstream_ci") == "tidb_integration_test_ci") {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/sqllogic-test-2'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
