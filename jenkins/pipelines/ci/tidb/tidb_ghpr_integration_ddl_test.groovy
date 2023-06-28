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
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
all_task_result = []
POD_NAMESPACE = "jenkins-tidb-mergeci"

GO_VERSION = "go1.20"
POD_GO_IMAGE = ""
POD_CLOUD = "kubernetes-ksyun"
POD_NAMESPACE = "jenkins-tidb"
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
    "go1.20": "hub.pingcap.net/jenkins/centos7_golang-1.20:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
    "go1.20": "${JOB_NAME}-go1200-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-upgrade-temporary.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}

podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

def run_with_pod(Closure body) {
    label = POD_LABEL_MAP[GO_VERSION]
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
    label = POD_LABEL_MAP[GO_VERSION]
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
    stage("Pre-check"){
        if (!params.force){
            node("lightweight_pod"){
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
                            make ddltest
                        fi
                        ls bin
                        rm -rf bin/tidb-server-*
                        cd ..
                        tar -czf tidb-server.tar.gz tidb
                        curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    stage('Integration DLL Test') {
        def tests = [:]

        def run = { test_dir, mytest, ddltest ->
            run_with_memory_volume_pod {

                def ws = pwd()
                deleteDir()

                container("golang") {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        timeout(10) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 10; done
                            """
                            def dir = pwd()
                            sh """
                            unset GOPROXY && go env -w GOPROXY=${GOPROXY} 
                            tidb_test_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"`
                            tidb_test_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/\${tidb_test_sha1}/centos7/tidb-test.tar.gz"

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

                            mkdir -p ${dir}/../tidb/
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 \${tidb_tar_url}
                            tar -xz -f tidb-server.tar.gz -C ${dir}/../ && rm -rf tidb-server.tar.gz
                            mv ${dir}/../tidb/bin/tidb-server ./bin/ddltest_tidb-server

                            cd ${dir}/../tidb/
                            GO111MODULE=on go mod vendor -v

                            mkdir -p ${dir}/../tidb_gopath/src
                            cd ${dir}/../tidb_gopath
                            if [ -d ../tidb/vendor/ ]; then
                                cp -rf ../tidb/vendor/* ./src
                                if [ -f ../tidb/go.mod ]; then
                                    mv ${dir}/../tidb/vendor ${dir}/../tidb/_vendor
                                fi
                            fi
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
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

                                export PATH=`pwd`/bin:\$PATH
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                if [ -f ${ws}/go/src/github.com/pingcap/tidb/bin/ddltest ]; then
                                    export DDLTEST_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/ddltest
                                fi
                                export log_level=debug
                                TIDB_SERVER_PATH=`pwd`/bin/ddltest_tidb-server \
                                GO111MODULE=off GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go ./test.sh -test.run='${ddltest}' 2>&1
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
            try {
                run("ddl_test", "ddl_insert_test", "^TestSimple.*Insert\$")
                all_task_result << ["name": "DDL Insert Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Insert Test", "status": "failed", "error": err.message]
                throw err
            } 
        }

        tests["Integration DDL Update Test"] = {
            try {
                run("ddl_test", "ddl_update_test", "^TestSimple.*Update\$")
                all_task_result << ["name": "DDL Update Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Update Test", "status": "failed", "error": err.message]
                throw err
            }  
        }

        tests["Integration DDL Delete Test"] = {
            try {
                run("ddl_test", "ddl_delete_test", "^TestSimple.*Delete\$")
                all_task_result << ["name": "DDL Delete Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Delete Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["Integration DDL Other Test"] = {
            try {
                run("ddl_test", "ddl_other_test", "^TestSimp(le\$|leMixed\$|leInc\$)")
                all_task_result << ["name": "DDL Other Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Other Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["Integration DDL Column Test"] = {
            try {
                run("ddl_test", "ddl_column_index_test", "^TestColumn\$")
                all_task_result << ["name": "DDL Column Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Column Test", "status": "failed", "error": err.message]
                throw err
            }
        }

        tests["Integration DDL Index Test"] = {
            try {
                run("ddl_test", "ddl_column_index_test", "^TestIndex\$")
                all_task_result << ["name": "DDL Index Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "DDL Index Test", "status": "failed", "error": err.message]
                throw err
            }
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
finally {
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
