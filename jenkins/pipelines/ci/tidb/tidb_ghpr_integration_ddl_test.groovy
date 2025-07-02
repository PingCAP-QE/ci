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

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
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

m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
all_task_result = []
POD_NAMESPACE = "jenkins-tidb-mergeci"
POD_CLOUD = "kubernetes-ksyun"

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

podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

def run_with_pod(Closure body) {
    label = POD_LABEL
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
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
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

def run_with_memory_volume_pod(Closure body) {
    label = POD_LABEL
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
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/go', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            println "this pod use memory volume"
            body()
        }
    }
}

try {
    run_with_pod {
        stage('Build') {
            def ws = pwd()
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    deleteDir()
                    def filepath = "builds/pingcap/tidb/ddl-test/centos7/${ghprbActualCommit}/tidb-server.tar.gz"
                    timeout(15) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 2; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                        tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                        unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                        if [ \$(grep -E "^ddltest:" Makefile) ]; then
                           ls bin/ddltest || make ddltest
                        fi
                        ls bin
                        rm -rf bin/tidb-server-*
                        """
                    }
                }
                stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
            }
        }
    }

    stage('Integration DLL Test') {
        def tests = [:]

        def run = { test_dir, mytest, ddltest ->
            run_with_memory_volume_pod {
                def ws = pwd()
                deleteDir()
                unstash "tidb"
                container("golang") {
                    dir("go/src/github.com/PingCAP-QE/tidb-test") {
                        timeout(10) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 10; done
                            """
                            def dir = pwd()
                            sh """
                            unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                            tidb_test_sha1=`curl "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"`
                            tidb_test_url="${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/\${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                            tidb_tar_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/ddl-test/centos7/${ghprbActualCommit}/tidb-server.tar.gz"

                            tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"

                            while ! curl --output /dev/null --silent --head --fail \${tidb_test_url}; do sleep 10; done
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 \${tidb_test_url}
                            tar -xz  -f tidb-test.tar.gz

                            cd ${test_dir}

                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 10; done
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${tikv_url}
                            tar -xz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz

                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 10; done
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${pd_url}
                            tar -xz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz

                            ls -alh ${WORKSPACE}/go/src/github.com/pingcap/tidb/bin/
                            cp ${WORKSPACE}/go/src/github.com/pingcap/tidb/bin/tidb-server ./bin/ddltest_tidb-server
                            cp ${WORKSPACE}/go/src/github.com/pingcap/tidb/bin/ddltest ./bin/ddltest
                            pwd && ls -alh ./bin/
                            """
                        }
                    }

                    dir("go/src/github.com/PingCAP-QE/tidb-test/${test_dir}") {
                        try {
                            timeout(40) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                killall -9 -r flash_cluster_manager
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e
                                unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10

                                ls -alh ${WORKSPACE}/go/src/github.com/PingCAP-QE/tidb-test/${test_dir}/
                                ls -alh ${WORKSPACE}/go/src/github.com/PingCAP-QE/tidb-test/${test_dir}/bin/
                                export PATH=${WORKSPACE}/go/src/github.com/PingCAP-QE/tidb-test/${test_dir}/bin:\$PATH
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                export DDLTEST_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/ddltest
                                export log_level=debug
                                export TIDB_SERVER_PATH="${ws}/go/src/github.com/PingCAP-QE/tidb-test/${test_dir}/bin/ddltest_tidb-server"
                                pwd && ./test.sh -test.run="${ddltest}"
                                """
                            }
                        } catch (err) {
                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat ./ddltest/tidb_log_file_* || true
                            cat tidb_log_file_*
                            """
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
        }

        tests["Integration DDL Insert Test"] = {
            run("ddl_test", "ddl_insert_test", "^TestSimple.*Insert\$")
        }

        tests["Integration DDL Update Test"] = {
            run("ddl_test", "ddl_update_test", "^TestSimple.*Update\$")
        }

        tests["Integration DDL Delete Test"] = {
            run("ddl_test", "ddl_delete_test", "^TestSimple.*Delete\$")
        }

        tests["Integration DDL Other Test"] = {
            run("ddl_test", "ddl_other_test", "^TestSimp(le\$|leMixed\$|leInc\$)")
        }

        tests["Integration DDL Column Test"] = {
            run("ddl_test", "ddl_column_index_test", "^TestColumn\$")
        }

        tests["Integration DDL Index Test"] = {
            run("ddl_test", "ddl_column_index_test", "^TestIndex\$")
        }

        parallel tests
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-ddl-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
