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

isNeedGo1160 = false
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

all_task_result = []


if (isNeedGo1160) {
    println "This build use go1.16"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
} else {
    println "This build use go1.13"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
}
POD_NAMESPACE = "jenkins-tidb"

def run_with_pod(Closure body) {
    def label = "tidb-ghpr-integration-ddl-test"
    if (isNeedGo1160) {
        label = "${label}-go1160-${BUILD_NUMBER}"
    } else {
        label = "${label}-go1130-${BUILD_NUMBER}"
    }
    def cloud = "kubernetes"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: POD_GO_DOCKER_IMAGE, ttyEnabled: true,
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

def run_with_memory_volume_pod(Closure body) {
    def label = "tidb-ghpr-integration-ddl-test-memory-volume"
    if (isNeedGo1160) {
        label = "${label}-go1160-${BUILD_NUMBER}"
    } else {
        label = "${label}-go1130-${BUILD_NUMBER}"
    }
    def cloud = "kubernetes"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: POD_GO_DOCKER_IMAGE, ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],  
                    )
            ],
            volumes: [
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                            emptyDirVolume(mountPath: '/tmp', memory: false),
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
                    def filepath = "builds/pingcap/tidb/ddl-test/centos7/${ghprbActualCommit}/tidb-server.tar"
                    timeout(5) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 2; done
                        curl ${tidb_url} | tar xz -C ./
                        if [ \$(grep -E "^ddltest:" Makefile) ]; then
                            make ddltest
                        fi
                        ls bin
                        rm -rf bin/tidb-server-*
                        cd ..
                        tar -cf tidb-server.tar tidb
                        curl -F ${filepath}=@tidb-server.tar ${FILE_SERVER_URL}/upload
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

                        // def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        // def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        // def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        // def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                        // tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                        // def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                        // def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                        // pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                        timeout(10) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 10; done
                            """
                            def dir = pwd()
                            sh """
                            tidb_test_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"`
                            tidb_test_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/\${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                            tidb_tar_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/ddl-test/centos7/${ghprbActualCommit}/tidb-server.tar"

                            tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"

                            while ! curl --output /dev/null --silent --head --fail \${tidb_test_url}; do sleep 10; done
                            curl \${tidb_test_url} | tar xz

                            cd ${test_dir}

                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 10; done
                            curl \${tikv_url} | tar xz

                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 10; done
                            curl \${pd_url} | tar xz

                            mkdir -p ${dir}/../tidb/
                            curl \${tidb_tar_url} | tar -xf - -C ${dir}/../
                            mv ${dir}/../tidb/bin/tidb-server ./bin/ddltest_tidb-server

                            cd ${dir}/../tidb/
                            export GOPROXY=https://goproxy.cn
                            GO111MODULE=on go mod vendor -v || true

                            mkdir -p ${dir}/../tidb_gopath/src
                            cd ${dir}/../tidb_gopath
                            if [ -d ../tidb/vendor/ ]; then cp -rf ../tidb/vendor/* ./src; fi

                            if [ -f ../tidb/go.mod ]; then mv ${dir}/../tidb/vendor ${dir}/../tidb/_vendor; fi
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        try {
                            timeout(10) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                killall -9 -r flash_cluster_manager
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e

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
                                export GOPROXY=https://goproxy.cn
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
