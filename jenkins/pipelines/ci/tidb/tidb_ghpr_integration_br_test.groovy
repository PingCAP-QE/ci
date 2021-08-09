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

catchError {
    node("${GO_TEST_SLAVE}") {
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
                if (ghprbTargetBranch == "release-4.0" || ghprbTargetBranch == "release-5.0" || ghprbTargetBranch == "release-5.1") {
                    default_params.triggered_by_upstream_pr_ci = "tidb"
                    // We tests BR on the same branch as TiDB's.
                    default_params.upstream_pr_ci_ghpr_actual_commit = "${ghprbTargetBranch}"
                }
                // Trigger BRIE test without waiting its finish.
                build(job: "br_ghpr_unit_and_integration_test", parameters: default_params, wait: true)
            }
            currentBuild.result = "SUCCESS"
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

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Trigger Integration BRIE Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS" && currentBuild.result != "ABORTED") {
        slackSend channel: '#jenkins-ci-migration', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
