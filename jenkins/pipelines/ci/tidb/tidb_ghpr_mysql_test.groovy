final releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
final featureBranchReg = /^feature[\/_].+/
final commentBodyReg = /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
final trunkBranch = 'master'

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
    if (branch in ["master"] ||
            branch.matches("^feature[/_].*") /* feature branches */ ||
            (branch.startsWith("release-") && branch >= "release-6.2")) {
        return true
    }
    return false
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""
ciErrorCode = 0

def upload_test_result(reportDir) {
    if (!fileExists(reportDir)){
        return
    }
    try {
        id=UUID.randomUUID().toString()
        def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
        resultDownloadPath = "${FILE_SERVER_URL}/download/${filepath}"
        sh """
        curl -F ${filepath}=@${reportDir} ${FILE_SERVER_URL}/upload
        """
        def downloadPath = "${FILE_SERVER_URL}/download/${filepath}"
        def all_results = [
            jenkins_job_name: "${JOB_NAME}",
            jenkins_url: "${env.RUN_DISPLAY_URL}",
            repo: "${ghprbGhRepository}",
            commit_id: ghprbActualCommit,
            branch: ghprbTargetBranch,
            junit_report_url: downloadPath,
            pull_request: ghprbPullId.toInteger(),
            author: ghprbPullAuthorLogin
        ]
        def json = groovy.json.JsonOutput.toJson(all_results)
        response = httpRequest consoleLogResponseBody: true, contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: json, url: "http://172.16.4.15:30792/report/", validResponseCodes: '200'
    }catch (Exception e) {
        // upload test case result to tipipeline, do not block ci
        print "upload test result to tipipeline failed, continue."
    }
}

def run_test_with_pod(Closure body) {
    def label = POD_LABEL
    def cloud = "kubernetes-ksyun"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
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
                    timeout(35) {
                        retry(3){
                            deleteDir()
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                        }
                    }
                }

                dir("go/src/github.com/PingCAP-QE/tidb-test") {
                    timeout(10) {
                        def TIDB_TEST_BRANCH = ghprbTargetBranch
                        if (ghprbCommentBody =~ commentBodyReg) {
                            TIDB_TEST_BRANCH = (ghprbCommentBody =~ commentBodyReg)[0][1]
                        } else if (ghprbTargetBranch =~ releaseOrHotfixBranchReg) {
                            TIDB_TEST_BRANCH = String.format('release-%s', (ghprbTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
                        } else if (ghprbTargetBranch =~ featureBranchReg) {
                            TIDB_TEST_BRANCH = trunkBranch
                        }
                        println "TIDB_TEST_BRANCH or PR: ${TIDB_TEST_BRANCH}"

                        def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb-test.tar.gz"
                        def cacheExisted = sh(returnStatus: true, script: """
                            if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                            """)
                        if (cacheExisted == 0) {
                            println "get code from fileserver to reduce clone time"
                            println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                            sh """
                            curl -C - --retry 3 -f -O ${codeCacheInFileserverUrl}
                            tar -xzf src-tidb-test.tar.gz --strip-components=1
                            rm -f src-tidb-test.tar.gz
                            """
                        } else {
                            println "get code from github"
                        }
                        def refspecTidbTest = "+refs/heads/*:refs/remotes/origin/*"
                        if (TIDB_TEST_BRANCH =~ /^pr\/(\d+$)/) {
                            // pull request
                            def pr = (TIDB_TEST_BRANCH =~ /^pr\/(\d+$)/)[0][1]
                            refspecTidbTest = "+refs/pull/${pr}/head:refs/remotes/origin/PR-${pr}"
                            checkout([$class: 'GitSCM', branches: [[name: "FETCH_HEAD"]],
                                extensions: [[$class: 'LocalBranch']],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: refspecTidbTest, url: 'git@github.com:PingCAP-QE/tidb-test.git']]])
                        } else {
                            checkout(changelog: false, poll: false, scm: [
                                $class: "GitSCM",
                                branches: [ [ name: TIDB_TEST_BRANCH ] ],
                                userRemoteConfigs: [
                                        [
                                                credentialsId: 'github-sre-bot-ssh',
                                                url: 'git@github.com:PingCAP-QE/tidb-test.git',
                                                refspec: refspecTidbTest,
                                        ]
                                ],
                                extensions: [
                                        [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', depth: 1, noTags: false, reference: '', shallow: true]
                                ],
                            ])
                        }
                        def githHash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                        println "tidb-test git hash: ${githHash}"
                        println "tidb-test branch or pull: ${TIDB_TEST_BRANCH}"
                        sh """
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd mysql_test && ./build.sh && cd ..
                        """
                        sh """
                            echo "stash tidb-test"
                            cd .. && tar -czf $TIDB_TEST_STASH_FILE tidb-test/
                            curl -F builds/PingCAP-QE/tidb-test/tmp/${TIDB_TEST_STASH_FILE}=@${TIDB_TEST_STASH_FILE} ${FILE_SERVER_URL}/upload
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
                    dir("go/src/github.com/PingCAP-QE") {
                        sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar xz
                            pwd && ls -alh
                        """
                    }
                    dir("go/src/github.com/PingCAP-QE/tidb-test/${test_dir}") {
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
                            if (parallel_run_mysql_test(ghprbTargetBranch)) {
                                junit testResults: "**/result.xml"
                                upload_test_result("result.xml")
                            }
                        }
                    }
                }
            }
        }
        def tests = [:]
        run_test_with_pod {
            container("golang") {
                if (parallel_run_mysql_test(ghprbTargetBranch)) {
                    println "run test in parallel with 4 parts"
                    tests["test part1"] = {run("mysql_test", "./test.sh -backlist=1 -part=1")}
                    tests["test part2"] = {run("mysql_test", "./test.sh -backlist=1 -part=2")}
                    tests["test part3"] = {run("mysql_test", "./test.sh -backlist=1 -part=3")}
                    tests["test part4"] = {run("mysql_test", "./test.sh -backlist=1 -part=4")}
                } else {
                    tests["mysql test"] = {run("mysql_test", "./test.sh")}
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
    build job: 'upload-pipelinerun-data',
        wait: false,
        parameters: [
                [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                [$class: 'StringParameterValue', name: 'REPO', value: "${ghprbGhRepository}"],
                [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
                [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
                [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
                [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
                [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: ghprbPullAuthorLogin],
                [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: ghprbPullAuthorLogin],
                [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: ghprbPullAuthorLogin],
                [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
                [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
                [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
                [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
                [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: ""],
                [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: ""],
                [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                [$class: 'StringParameterValue', name: 'PIPLINE_RUN_ERROR_CODE', value: "${ciErrorCode}"],
    ]
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
