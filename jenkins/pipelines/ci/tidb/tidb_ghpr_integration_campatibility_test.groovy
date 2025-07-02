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

POD_CLOUD = "kubernetes-ksyun"
POD_NAMESPACE = "jenkins-tidb"
GOPROXY="http://goproxy.apps.svc,https://proxy.golang.org,direct"

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
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

all_task_result = []
def notRun = 1

try {
    stage("Pre-check"){
        if (!params.force){
            node("master"){
                notRun = sh(returnStatus: true, script: """
                if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
                """)
            }
        }

        if (notRun == 0){
            println "the ${ghprbActualCommit} has been tested"
            throw new RuntimeException("hasBeenTested")
        }
    }

    stage('Prepare') {

        run_with_pod {
            def ws = pwd()
            deleteDir()

            container("golang") {

                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                        tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                        """
                    }
                }

                dir("go/src/github.com/PingCAP-QE/tidb-test") {
                    timeout(30) {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                        """
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O tidb-test.tar.gz ${tidb_test_url}
                        tar -xz -f tidb-test.tar.gz && rm -rf tidb-test.tar.gz
                        unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                        cd compatible_test && ./build.sh
                        """
                    }
                }
            }

            stash includes: "go/src/github.com/PingCAP-QE/tidb-test/compatible_test/**", name: "compatible_test"
            deleteDir()
        }
    }

    stage('Integration Compatibility Test') {
        try {
            run_with_pod {
                def ws = pwd()
                deleteDir()
                unstash 'compatible_test'

                dir("go/src/github.com/PingCAP-QE/tidb-test/compatible_test") {
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
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tikv_url}
                            tar -xz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz

                            while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${pd_url}
                            tar -xz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz

                            mkdir -p ./tidb-old-src
                            echo ${tidb_old_url}
                            echo ${tidb_old_refs}
                            while ! curl --output /dev/null --silent --head --fail ${tidb_old_url}; do sleep 15; done
                            wget -O old-tidb-server.tar.gz -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_old_url}
                            tar -xz -C ./tidb-old-src -f old-tidb-server.tar.gz && rm -rf old-tidb-server.tar.gz

                            mkdir -p ./tidb-src
                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                            wget -O tidb-server.tar.gz -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                            tar -xz -C ./tidb-src -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz


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
                                unset GOPROXY && go env -w GOPROXY=${GOPROXY}
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
            all_task_result << ["name": "Integration Compatibility Test", "status": "success", "error": ""]
        } catch (err) {
            all_task_result << ["name": "Integration Compatibility Test", "status": "failed", "error": err.message]
            throw err
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
        def json = groovy.json.JsonOutput.toJson(all_task_result)
        println "all_results: ${json}"
        currentBuild.description = "${json}"
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
