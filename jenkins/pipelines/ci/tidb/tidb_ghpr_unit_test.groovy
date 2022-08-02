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

specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}
if (params.containsKey("release_test")) {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

GO_VERSION = "go1.18"
RESOURCE_REQUEST_CPU = '6000m'
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "bazel_master": "hub.pingcap.net/wangweizhen/tidb_image:20220802",
]
POD_LABEL_MAP = [
    "go1.13": "tidb-ghpr-unit-test-go1130-${BUILD_NUMBER}",
    "go1.16": "tidb-ghpr-unit-test-go1160-${BUILD_NUMBER}",
    "go1.18": "tidb-ghpr-unit-test-go1180-${BUILD_NUMBER}",
    "bazel_master": "tidb-ghpr-unit-test-go1180-${BUILD_NUMBER}",
]
VOLUMES = [
    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
    emptyDirVolume(mountPath: '/tmp', memory: false),
]

def user_bazel(branch) {
    if (branch in ["master"]) {
        return true
    }
    if (branch.startsWith("release-") && branch >= "release-6.2") {
        return true
    }
    return false
}

node("master") {
    if (user_bazel(ghprbTargetBranch)) { 
        GO_VERSION = "bazel_master"
        RESOURCE_REQUEST_CPU = '4000m'
    } else {
        deleteDir()
        def ws = pwd()
        sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
        def script_path = "${ws}/goversion-select-lib.groovy"
        def goversion_lib = load script_path
        GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
        VOLUMES.add(emptyDirVolume(mountPath: '/home/jenkins', memory: false))
    }
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""
label = "tidb_ghpr_unit_test-${BUILD_NUMBER}"
def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: RESOURCE_REQUEST_CPU, resourceRequestMemory: '16Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')], 
                    ),
                    containerTemplate(
                            name: 'ruby', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/centos7_ruby-2.6.3:latest", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat', 
                    )
            ],
            volumes: VOLUMES,
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "go image: ${POD_GO_IMAGE}"
            timeout(time: 60, unit: 'MINUTES') {
               body() 
            }
        }
    }
}

def upload_test_result(reportDir) {
    if (!fileExists(reportDir)){
        return
    }
    try {
        id=UUID.randomUUID().toString()
        def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
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

try {
    run_with_pod {
        def ws = pwd()

        stage("Checkout") {
            container("golang") {
                sh "whoami && go version"
            }
            // update code
            dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
                container("golang") {
                    if(fileExists("/home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz")){
                        timeout(5) {
                            sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                                mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                            """
                        }
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                    }   catch (info) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 5
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                            }
                    }
                    container("golang") {
                        def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
                        timeout(5) {
                            sh """
                            git checkout -f ${ghprbActualCommit}
                            """
                            sh """
                            sed -ir "s:-project=github.com/pingcap/tidb:-project=${tidb_path}:g" Makefile
                            """
                        }
                    }
                }
            }
        }

        stage("Test") {
            def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    try {
                        if (user_bazel(ghprbTargetBranch)) { 
                            sh """
                                ./build/jenkins_unit_test.sh
                            """
                        } else {
                            sh """
                                ulimit -c unlimited
                                export GOBACTRACE=crash
                                export log_level=warn

                                if grep -q "br_unit_test_in_verify_ci" Makefile; then
                                    make br_unit_test_in_verify_ci
                                    mv test_coverage/br_cov.unit_test.out br.coverage
                                elif grep -q "br_unit_test" Makefile; then
                                    make br_unit_test
                                else
                                    echo "not found br_unit_test or br_unit_test_in_verify_ci"
                                fi
                                if grep -q "dumpling_unit_test_in_verify_ci" Makefile; then
                                    make dumpling_unit_test_in_verify_ci
                                    mv test_coverage/dumpling_cov.unit_test.out dumpling.coverage
                                elif grep -q "dumpling_unit_test" Makefile; then
                                    make dumpling_unit_test
                                else
                                    echo "not found dumpling_unit_test or dumpling_unit_test_in_verify_ci"
                                fi
                                if grep -q "gotest_in_verify_ci" Makefile; then
                                    make gotest_in_verify_ci
                                    mv test_coverage/tidb_cov.unit_test.out tidb.coverage
                                else
                                    make gotest
                                fi
                                """
                            } 
                    }catch (Exception e) {
                        archiveArtifacts artifacts: '**/core.*', allowEmptyArchive: true
                        archiveArtifacts artifacts: '**/*.test.bin', allowEmptyArchive: true
                        throw e
                    } finally {
                        if (user_bazel(ghprbTargetBranch)) { 
                            junit testResults: "**/bazel.xml", allowEmptyResults: true
                            try {
                                def id=UUID.randomUUID().toString()
                                def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
                                sh """
                                curl -F ${filepath}=@test_coverage/bazel.xml ${FILE_SERVER_URL}/upload
                                """
                                resultDownloadPath = "${FILE_SERVER_URL}/download/${filepath}"
                            } catch (Exception e) {
                                // upload test case result to fileserver, do not block ci
                                print "upload test result to fileserver failed, continue."
                            }
                            // upload_test_result("test_coverage/bazel.xml")
                        } else {
                            junit testResults: "**/*-junit-report.xml", allowEmptyResults: true
                            // upload_test_result("test_coverage/tidb-junit-report.xml")
                            // upload_test_result("test_coverage/br-junit-report.xml")
                            // upload_test_result("test_coverage/dumpling-junit-report.xml")
                        }
                    }
                    withCredentials([string(credentialsId: 'codecov-token-tidb', variable: 'CODECOV_TOKEN')]) {
                        timeout(5) {
                            if (ghprbPullId != null && ghprbPullId != "") {
                                if (user_bazel(ghprbTargetBranch)) { 
                                    sh """
                                    codecov -f "./coverage.dat" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}
                                    """
                                } else {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "dumpling.coverage" -f "br.coverage" -f "tidb.coverage"  -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER}
                                    """
                                }
                            } else {
                                if (user_bazel(ghprbTargetBranch)) { 
                                    sh """
                                    codecov -f "./coverage.dat" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                    """
                                } else {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "dumpling.coverage" -f "br.coverage" -f "tidb.coverage" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                    """
                                }     
                            }
                        }
                    }
                }
                container("ruby") {
                    withCredentials([string(credentialsId: "sre-bot-token", variable: 'GITHUB_TOKEN')]) {
                        timeout(5) {
                            if (ghprbPullId != null && ghprbPullId != "") { 
                            sh """#!/bin/bash
                            ruby --version
                            gem --version
                            wget ${FILE_SERVER_URL}/download/cicd/scripts/comment-on-pr.rb
                            ruby comment-on-pr.rb "pingcap/tidb" "${ghprbPullId}"  "Code Coverage Details: https://codecov.io/github/pingcap/tidb/commit/${ghprbActualCommit}" true "Code Coverage Details:"
                            """
                            }
                        }
                    }
                }
            }
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
    if (e.getMessage().equals("NotRunInPR")) {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/unit-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}

