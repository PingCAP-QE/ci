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

echo "trigger by upstream job: ${params.containsKey("upstreamJob")}"
if (params.containsKey("upstreamJob")) {
    upstreamJob = params.get("upstreamJob")
    println "upstreamJob: ${upstreamJob}"
    ghprbTargetBranch=params.getOrDefault("ghprbTargetBranch", "")
    ghprbCommentBody=params.getOrDefault("ghprbCommentBody", "")
    ghprbActualCommit=params.getOrDefault("ghprbActualCommit", "")
    ghprbPullId=params.getOrDefault("ghprbPullId", "")
    ghprbPullTitle=params.getOrDefault("ghprbPullTitle", "")
    ghprbPullLink=params.getOrDefault("ghprbPullLink", "")
    ghprbPullDescription=params.getOrDefault("ghprbPullDescription", "")
    println "ghprbTargetBranch: ${ghprbTargetBranch}"
    println "ghprbCommentBody: ${ghprbCommentBody}"
    println "ghprbActualCommit: ${ghprbActualCommit}"
}

def TIDB_TEST_BRANCH = ghprbTargetBranch
// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_TEST_BRANCH or PR: ${TIDB_TEST_BRANCH}"

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}

POD_NAMESPACE = "jenkins-tidb"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def TIDB_TEST_STASH_FILE = "tidb_test_mysql_test_${UUID.randomUUID().toString()}.tar"

echo "trigger by upstream job: ${params.containsKey("upstreamJob")}"
if (params.containsKey("upstreamJob")) {
    upstreamJob = params.get("upstreamJob")
    println "upstreamJob: ${upstreamJob}"
    tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${ghprbTargetBranch}/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
    tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${ghprbTargetBranch}/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
}

if (ghprbTargetBranch in ["br-stream"]) {
    println "This PR is for feature branch"
    println "Skip mysql_test ci for feature branch: ${ghprbTargetBranch}"
    return 0
}

def parallel_run_mysql_test(branch) {
    if (branch in ["master"]) {
        return true
    }
    if (branch.startsWith("release-") && branch >= "release-6.2") {
        return true
    }
    return false
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

def run_test_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
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
    stage('Prepare') {
        run_test_with_pod {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap/tidb-test") {
                    timeout(10) {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 5; done
                        """
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                        sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                            curl ${tidb_test_url} | tar xz
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd mysql_test && ./build.sh && cd ..      
                        """

                        sh """
                            echo "stash tidb-test"
                            cd .. && tar -czf $TIDB_TEST_STASH_FILE tidb-test/
                            curl -F builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE}=@${TIDB_TEST_STASH_FILE} ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
            deleteDir()
        }
    }

    stage('MySQL Test') {
        def run = { test_dir, test_cmd ->
            run_test_with_pod {
                def ws = pwd()
                def log_path = "mysql-test.out*"
                deleteDir()
                println "work space path:\n${ws}"
                container("golang") {
                    dir("go/src/github.com/pingcap/tidb") {
                        timeout(10) {
                            retry(3){
                                deleteDir()
                                sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                    curl ${tidb_url} | tar xz
                                """
                            }
                        }
                    }
                    dir("go/src/github.com/pingcap") {
                        sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar xz
                            pwd && ls -alh
                        """
                    }
                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        try {
                            timeout(25) {
                            sh """
                            pwd && ls -alh
                            TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                            ${test_cmd}
                            """ 
                            }
                        } catch (err) {
                            sh "cat ${log_path}"
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e
                            """
                            throw err
                        } finally {
                            if (parallel_run_test(ghprbTargetBranch)) {
                                junit testResults: "**/result.xml"
                            }
                        }
                    }
                }
            }
        }
        def tests = [:]
        run_test_with_pod {
            container("golang") {
                if (parallel_run_test(ghprbTargetBranch)) {
                    println "run test in parallel with 4 parts"
                    tests["test part1"] = {run("mysql_test", "./test.sh -backlist=1 -part=1")}
                    tests["test part2"] = {run("mysql_test", "./test.sh -backlist=1 -part=2")}
                    tests["test part3"] = {run("mysql_test", "./test.sh -backlist=1 -part=3")}
                    tests["test part4"] = {run("mysql_test", "./test.sh -backlist=1 -part=4")}
                } else {
                    tests["mysql-test"] = run("mysql-test", "./test.sh")
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
    taskFinishTime = System.currentTimeMillis()
    // build job: 'upload-pipelinerun-data',
    //     wait: false,
    //     parameters: [
    //             [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
    //             [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
    //             [$class: 'StringParameterValue', name: 'REPO', value: "${ghprbGhRepository}"],
    //             [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
    //             [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
    //             [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
    //             [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
    //             [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: ghprbPullAuthorLogin],
    //             [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: ghprbPullAuthorLogin],
    //             [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: ghprbPullAuthorLogin],
    //             [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
    //             [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
    //             [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
    //             [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
    //             [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: ""],
    //             [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: ""],
    //             [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
    //             [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
    // ]
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/mysql-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
