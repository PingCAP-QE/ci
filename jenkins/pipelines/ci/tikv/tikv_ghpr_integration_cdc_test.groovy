package ci.tikv

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def BUILD_NUMBER = "${env.BUILD_NUMBER}"
def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"

catchError {
    node("${GO_TEST_SLAVE}") {
        stage('Trigger TiCDC Test') {
            if (ghprbTargetBranch == "master" || ghprbTargetBranch == "release-4.0" || ghprbTargetBranch == "release-5.0" || ghprbTargetBranch == "release-5.1") {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    // Wait build finish.
                    timeout(60) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail "${tikv_url}"; do sleep 5; done
                        """
                    }

                    def default_params = [
                            booleanParam(name: 'force', value: true),
                            string(name: 'triggered_by_upstream_pr_ci', value: "tikv"),
                            string(name: 'upstream_pr_ci_ghpr_target_branch', value: "${ghprbTargetBranch}"),
                            string(name: 'upstream_pr_ci_ghpr_actual_commit', value: "${ghprbActualCommit}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_id', value: "${ghprbPullId}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_title', value: "${ghprbPullTitle}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_link', value: "${ghprbPullLink}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_description', value: "${ghprbPullDescription}"),
                            string(name: 'upstream_pr_ci_override_tikv_download_link', value: "${tikv_url}"),
                    ]

                    // Trigger TiCDC test and waiting its finish.
                    build(job: "cdc_ghpr_integration_test", parameters: default_params, wait: true)
                }
            } else {
                println "skip trigger TiCDC tests as this PR targets to ${ghprbTargetBranch}"
            }
            currentBuild.result = "SUCCESS"
        }
    }
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Trigger Integration TiCDC Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS" && currentBuild.result != "ABORTED") {
        slackSend channel: '#jenkins-ci-migration', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
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
                    string(name: 'TIKV_COMMIT_ID', value: ghprbActualCommit),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci/integration-cdc-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL),
                    string(name: 'STATUS', value: PARAM_STATUS),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
