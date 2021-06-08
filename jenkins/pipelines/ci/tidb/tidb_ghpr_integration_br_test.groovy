def BUILD_NUMBER = "${env.BUILD_NUMBER}"
def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"

catchError {
    node("${GO_TEST_SLAVE}") {
        stage('Trigger BRIE Test') {
            if (ghprbTargetBranch == "master" || ghprbTargetBranch == "release-4.0" || ghprbTargetBranch == "release-5.0") {
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
                            string(name: 'triggered_by_upstream_pr_ci', value: "tidb"),
                            string(name: 'upstream_pr_ci_ghpr_target_branch', value: "${ghprbTargetBranch}"),
                            // We tests BR on the same branch as TiDB's.
                            string(name: 'upstream_pr_ci_ghpr_actual_commit', value: "${ghprbTargetBranch}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_id', value: "${ghprbPullId}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_title', value: "${ghprbPullTitle}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_link', value: "${ghprbPullLink}"),
                            string(name: 'upstream_pr_ci_ghpr_pull_description', value: "${ghprbPullDescription}"),
                            string(name: 'upstream_pr_ci_override_tidb_download_link', value: "${tidb_url}"),
                    ]

                    // Trigger BRIE test without waiting its finish.
                    build(job: "br_ghpr_unit_and_integration_test", parameters: default_params, wait: true)
                }
            } else {
                println "skip trigger BRIE tests as this PR targets to ${ghprbTargetBranch}"
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
            "Trigger Integration BRIE Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS" && currentBuild.result != "ABORTED") {
        slackSend channel: '#jenkins-ci-migration', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
