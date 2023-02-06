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

def BUILD_NUMBER = "${env.BUILD_NUMBER}"
def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it
    }

result = ""
triggered_job_name = "br_ghpr_unit_and_integration_test"


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb-mergeci"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    def go_image = "hub.pingcap.net/jenkins/centos7_golang-1.18:latest"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
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
        // After BR merged into TiDB, every PR should trigger this test.
        stage('Trigger BRIE Test') {
            container("golang") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                // Wait build finish.
                timeout(60) {
                    sh """
                    while ! curl --output /dev/null --silent --head --fail "${tidb_url}"; do sleep 5; done
                    """
                }

                def default_params = [
                        booleanParam(name: 'force', value: true),
                        // "tidb-br" run every br integration tests after BR merged into TiDB.
                        string(name: 'triggered_by_upstream_pr_ci', value: "tidb-br"),
                        string(name: 'upstream_pr_ci_ghpr_target_branch', value: "${ghprbTargetBranch}"),
                        string(name: 'upstream_pr_ci_ghpr_actual_commit', value: "${ghprbActualCommit}"),
                        string(name: 'upstream_pr_ci_ghpr_pull_id', value: "${ghprbPullId}"),
                        string(name: 'upstream_pr_ci_ghpr_pull_title', value: "${ghprbPullTitle}"),
                        string(name: 'upstream_pr_ci_ghpr_pull_link', value: "${ghprbPullLink}"),
                        string(name: 'upstream_pr_ci_ghpr_pull_description', value: "${ghprbPullDescription}"),
                        string(name: 'upstream_pr_ci_override_tidb_download_link', value: "${tidb_url}"),
                ]

                // these three branch don't have br integrated.
                targetBranch = ghprbTargetBranch
                if(ghprbTargetBranch.startsWith("release-")) {
                    // remove date from branch name
                    // before: release-5.1-20210909
                    // after: release-5.1
                    targetBranch = "release-" + trimPrefix(ghprbTargetBranch)
                }
                if (targetBranch == "release-4.0" || targetBranch == "release-5.0" || targetBranch == "release-5.1") {
                    default_params[1] = string(name: 'triggered_by_upstream_pr_ci', value: "tidb")
                    // We tests BR on the same branch as TiDB's.
                    default_params[3] = string(name: 'upstream_pr_ci_ghpr_actual_commit', value: "${targetBranch}")
                }
                // Trigger BRIE test without waiting its finish.
                result = build(job: "br_ghpr_unit_and_integration_test", parameters: default_params, wait: true, propagate: false)
                if (result.getResult() != "SUCCESS") {
                    echo "Test failed: https://ci.pingcap.net/blue/organizations/jenkins/br_ghpr_unit_and_integration_test/detail/br_ghpr_unit_and_integration_test/${result.number}/pipeline"
                    throw new Exception("triggered job: br_ghpr_unit_and_integration_test failed")
                } else {
                    echo "Test at: https://ci.pingcap.net/blue/organizations/jenkins/br_ghpr_unit_and_integration_test/detail/br_ghpr_unit_and_integration_test/${result.number}/pipeline"
                }
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
                sh "curl -O ${triggered_job_result_file_url}"
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


if (params.containsKey("triggered_by_upstream_ci") && params.get("triggered_by_upstream_ci") == "tidb_integration_test_ci") {
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
                    booleanParam(name: 'ENABLE_FAIL_FAST', value: false),
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-br-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}

