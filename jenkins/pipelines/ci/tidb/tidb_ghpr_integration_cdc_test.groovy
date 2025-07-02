package ci.tidb

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
// dailyci or mergeci trigger this ci, use different binary download url
if (ghprbPullId == null || ghprbPullId == "") {
    // builds/pingcap/tidb-check/pr/f5c2710cd493de9ad99ca79a652d64f349d05425/centos7/tidb-server.tar.gz
    tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
}

result = ""
triggered_job_name = "cdc_ghpr_integration_test"


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tidb-mergeci"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    def go_image = "hub.pingcap.net/jenkins/centos7_golang-1.18:latest"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${go_image}", ttyEnabled: true,
                        resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
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
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

run_with_pod {
    try {
        stage('Trigger TiCDC Integration Test') {
            if (ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-")) {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    // Wait build finish.
                    timeout(3) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail "${tidb_url}"; do sleep 5; done
                        """
                    }
                    def tiflowGhprbActualCommit = sh(returnStdout: true, script: "curl -f ${FILE_SERVER_URL}/download/refs/pingcap/tiflow/${ghprbTargetBranch}/sha1").trim()
                    println("tiflow latest commit on ${ghprbTargetBranch}: ${tiflowGhprbActualCommit}")
                    println "tidb binary url: ${tidb_url}"
                    def default_params = [
                            booleanParam(name: 'force', value: true),
                            booleanParam(name: 'ENABLE_FAIL_FAST', value: false),
                            string(name: 'triggered_by_upstream_pr_ci', value: "tidb"),
                            string(name: 'upstream_pr_ci_ghpr_target_branch', value: "${ghprbTargetBranch}"),
                            // We use the latest commit build binary which cached in file server to run test.
                            string(name: 'upstream_pr_ci_ghpr_actual_commit', value: "${tiflowGhprbActualCommit}"),
                            // We set the pull id to empty string here because we download the code with the specified commit.
                            string(name: 'upstream_pr_ci_ghpr_pull_id', value: ""),
                            string(name: 'upstream_pr_ci_ghpr_pull_title', value: "${ghprbPullTitle}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_link', value: "${ghprbPullLink}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_description', value: "${ghprbPullDescription}"),
                            string(name: 'upstream_pr_ci_override_tidb_download_link', value: "${tidb_url}"),
                    ]

                    // Trigger TiCDC test and waiting its finish.
                    result = build(job: "${triggered_job_name}", parameters: default_params, wait: true, propagate: false)
                    if (result.getResult() != "SUCCESS") {
                        echo "Test failed: https://ci.pingcap.net/blue/organizations/jenkins/cdc_ghpr_integration_test/detail/cdc_ghpr_integration_test/${result.number}/pipeline"
                        throw new Exception("triggered job: cdc_ghpr_integration_test failed")
                    } else {
                        echo "Test at: https://ci.pingcap.net/blue/organizations/jenkins/cdc_ghpr_integration_test/detail/cdc_ghpr_integration_test/${result.number}/pipeline"
                    }
                }
            } else {
                println "skip trigger TiCDC tests as this PR targets to ${ghprbTargetBranch}"
            }
            currentBuild.result = "SUCCESS"
        }
    } catch(e) {
        println "error: ${e}"
        currentBuild.result = "FAILURE"
    } finally {
        container("golang") {
            def triggered_job_result_file_url = "${FILE_SERVER_URL}/download/cicd/ci-pipeline-artifacts/result-${triggered_job_name}_${result.getNumber().toString()}.json"
            def file_existed = sh(returnStatus: true,
                                script: """if curl --output /dev/null --silent --head --fail ${triggered_job_result_file_url}; then exit 0; else exit 1; fi""")
            if (file_existed == 0) {
                sh "curl -fO ${triggered_job_result_file_url}"
                def jsonObj = readJSON file: "result-${triggered_job_name}_${result.getNumber().toString()}.json"
                def json = groovy.json.JsonOutput.toJson(jsonObj)
                println "all_results: ${json}"
                currentBuild.description = "${json}"
                writeJSON file: 'ciResult.json', json: json, pretty: 4
                sh 'cat ciResult.json'
                archiveArtifacts artifacts: 'ciResult.json', fingerprint: true
            } else {
                println "triggered job result file not exist"
            }
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
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci/integration-cdc-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL),
                    string(name: 'STATUS', value: PARAM_STATUS),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
