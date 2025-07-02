
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

POD_CLOUD = "kubernetes-ksyun"
POD_NAMESPACE = "jenkins-tidb"
GO_VERSION = "go1.21"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.21:latest"
POD_LABEL = "${JOB_NAME}-${BUILD_NUMBER}-go121"

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-v2.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = goversion_lib.selectGoImage(ghprbTargetBranch)
    POD_LABEL = goversion_lib.getPodLabel(ghprbTargetBranch, JOB_NAME, BUILD_NUMBER)
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
    println "pod label: ${POD_LABEL}"
}


def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"

podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

def run_with_pod(Closure body) {
    def label = POD_LABEL
    podTemplate(label: label,
            cloud: POD_CLOUD,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/go', memory: false),
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
    stage('Prepare') {
        run_with_pod {
            def ws = pwd()
            deleteDir()

            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                        tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                        # use tidb-server with ADMIN_CHECK as default
                        mkdir -p ${ws}/go/src/github.com/PingCAP-QE/tidb-test/sqllogic_test/
                        mv bin/tidb-server-check ${ws}/go/src/github.com/PingCAP-QE/tidb-test/sqllogic_test/tidb-server
                        """
                    }
                }
            }

            dir("go/src/github.com/PingCAP-QE/tidb-test") {
                container("golang") {
                    timeout(5) {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                        """
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                        sh """
                        unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O tidb-test.tar.gz ${tidb_test_url}
                        tar -xz -f tidb-test.tar.gz && rm -rf tidb-test.tar.gz
                        cd sqllogic_test && ./build.sh
                        """
                    }
                }
            }

            stash includes: "go/src/github.com/PingCAP-QE/tidb-test/sqllogic_test/**", name: "tidb-test"
            deleteDir()
        }
    }

    stage('SQL Logic Test') {
        def run = { sqllogictest, parallelism, enable_cache ->
            run_with_pod {
                deleteDir()
                unstash 'tidb-test'

                dir("go/src/github.com/PingCAP-QE/tidb-test/sqllogic_test") {
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
                                unset GOPROXY && go env -w GOPROXY=${GOPROXY}
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
            run_with_pod {
                deleteDir()
                unstash 'tidb-test'

                dir("go/src/github.com/PingCAP-QE/tidb-test/sqllogic_test") {
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
                                unset GOPROXY && go env -w GOPROXY=${GOPROXY}
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

        if (ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-")) {
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
