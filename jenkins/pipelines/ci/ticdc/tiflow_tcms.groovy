properties([
        parameters([
                string(
                        defaultValue: '6a9296064f80f4322649123044f30cf837d0350a',
                        name: 'ghprbActualCommit',
                        trim: true
                ),
                string(
                        defaultValue: 'cli-use-open-api',
                        name: 'ghprbTargetBranch',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ghprbPullId',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'ghprbCommentBody',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ghprbPullLink',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'ghprbPullTitle',
                        trim: true
                )
        ])
])


if (params.containsKey("release_test")) {
    ghprbActualCommit = params.release_test__cdc_commit
    ghprbTargetBranch = params.release_test__release_branch
    ghprbPullId = ""
    ghprbCommentBody = ""
    ghprbPullLink = "release-test"
    ghprbPullTitle = "release-test"
    ghprbPullDescription = "release-test"
}



def check_plan_status(plan_id) {
    success = true
    timeout(time: 120, unit: 'MINUTES') {
        stage("wait for plans execution to end"){
            finishedStatus = ["\"SUCCESS\"","FAILURE","ERROR"]
            errorfinishedStatus = ["FAILURE","ERROR"]
            still_running = "1"
            while (still_running == "1") {
                status = sh(returnStdout: true,
                    script:  """
                    curl 'https://tcms.pingcap.net/api/v1/plan-executions/${plan_id}' \
                    -H 'authority: tcms.pingcap.net' \
                    -H 'accept: */*' \
                    -H 'authorization: Bearer tcmsp_JFUYWuanEIeYMxmoWLGj' \
                    --compressed | jq '.status'
                    """).trim()
                println "test plan status: ${status}"
                if (status in finishedStatus) {
                    still_running = "0"
                    success = true
                    println "finished ${still_running}"
                } else if (status in errorfinishedStatus) {
                    still_running = "0"
                    success = false
                    println "error ${still_running}"
                } else {
                    println "sleep ${still_running}"
                    sleep(time: 5, unit: 'SECONDS')
                }
                println "still running ${still_running}"

                // if (finishedStatus.contains(status)) {
                //     still_running = false
                //     println "test plan finished: ${still_running}"
                //     if (errorfinishedStatus.contains(status)) {
                //         success = false
                //     }
                // }
            }
        }
    }

    return success
}


def check_case_result(plan_id) {
    def case_result = sh(returnStatus: true,
            script: """
            curl 'https://tcms.pingcap.net/api/v1/case-executions?offset=0&count=20&root_plan_exec_id=${plan_id}' \
                -H 'authority: tcms.pingcap.net' \
                -H 'accept: */*' \
                -H 'authorization: Bearer tcmsp_JFUYWuanEIeYMxmoWLGj' \
                --compressed | jq -r '.data[] | "\\(.stepName)\\t\\(.status)"' | column -t
            """)
    return case_result
}



def label = "${JOB_NAME}-${BUILD_NUMBER}"
def cloud = "kubernetes-ng"
def namespace = "jenkins-tidb-mergeci"
def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
podTemplate(label: label,
        cloud: cloud,
        namespace: namespace,
        idleMinutes: 0,
        containers: [
                containerTemplate(
                    name: 'golang', alwaysPullImage: true,
                    image: "hub.pingcap.net/jenkins/centos7_golang-1.18:latest", ttyEnabled: true,
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
        println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
        container("golang") {
            stage("prepare") {
                sh """
                curl -LO http://fileserver.pingcap.net/download/cicd/ticdc-feature-branch/cdc_boundary_test_sync.yaml
                sed -i -e 's|TICDC_FEATURE_BRANCH_VERSION|${ghprbTargetBranch}|g' cdc_boundary_test_sync.yaml
                curl http://fileserver.pingcap.net/download/pingcap/qa/test-infra/tcctl-install.sh | bash
                tcctl config --token tcmsp_JFUYWuanEIeYMxmoWLGj --rms-host 'http://rms-staging.pingcap.net:30007'
                tcctl run -f cdc_boundary_test_sync.yaml | tee cdc_boundary_test_sync.log
                # test_plan_id=$(cat cdc_boundary_test_sync.log | grep https | cut -d ' ' -f14 | cut -d '/' -f7)

                """
                TEST_PLAN_ID = sh(script: "cat cdc_boundary_test_sync.log | grep https | cut -d ' ' -f14 | cut -d '/' -f7", returnStdout: true).trim()
                // TEST_PLAN_ID = "870090"
                println "test plan id: ${TEST_PLAN_ID}"
                if (TEST_PLAN_ID == "") {
                    echo "test plan id is empty"
                    exit 1
                }
                test_result = check_plan_status(TEST_PLAN_ID)
                if (test_result) {
                    currentBuild.result = "SUCCESS"
                }
                case_details = check_case_result(TEST_PLAN_ID)
            }
        }
    }
}


if (params.containsKey("triggered_by_upstream_ci")  && params.get("triggered_by_upstream_ci") == "tiflow_merge_ci") {
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
                    string(name: 'TIFLOW_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci/feature-branch-tcms'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tiflow_update_commit_status", parameters: default_params, wait: true)
        }
    }
}




