
// example commit msg
// expression: fix wrong result type for greatest/least (#29408) (#29912)
// close #29019
@NonCPS
def extract_pull_id(MSG){
    def resp = []
    def m1 = MSG =~ /(\#\b\d+)/
    if (m1) {
        for (int i = 0; i < m1.count; i++ ) {
            println m1[i][0]
            resp.add(m1[i][0])
        }
    }
    m1 = null

    return resp
}

@NonCPS // has to be NonCPS or the build breaks on the call to .each
def parseBuildResult(list) {
    def total_test = 0
    def failed_test = 0
    def success_test = 0

    list.each { item ->
        echo "${item}"
        if (item.status == "success") {
            success_test += 1
        } else {
            failed_test += 1
        }
    }
    total_test = success_test + failed_test
    def resp_str = ""
    if (failed_test > 0) {
        resp_str = "failed ${failed_test}, success ${success_test}, total ${total_test}"
    } else {
        resp_str = "all ${total_test} tests passed"
    }

    return resp_str
}


// Debug Env
// TODO need to remove those lines after debug
// REF = "refs/heads/master  d660e483c2cf3df13d891a38fa29bcae53c52a08"
// ref = "refs/heads/master"
// GEWT_COMMIT_MSG = "sessionctx: fix the value of analyze_version when upgrading 4.x to 5.… (#30743)"
// GEWT_AUTHOR = "purelind"
// GEWT_AUTHOR_EMAIL = "purelind@gmail.com"
// GEWT_PULL_ID = "#30743"
// TIDB_COMMIT_ID = "d660e483c2cf3df13d891a38fa29bcae53c52a08"
// TIDB_BRANCH = "master"




// Notify
// PR author from PingCAP will receive a notification by company email and lark.
// PR author from community will receive a notification by github email.

// title: tidb-merge-ci #build-id

def taskStartTimeInMillis = System.currentTimeMillis()

node("github-status-updater") {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            println "${ref}"
            TIDB_BRANCH = trimPrefix(ref)
            // def m1 = GEWT_COMMIT_MSG =~ /(?<=\()(.*?)(?=\))/
            // if (m1) {
            //     GEWT_PULL_ID = "${m1[0][1]}"
            //     GEWT_PULL_ID = GEWT_PULL_ID.substring(1)
            // }
            // m1 = null
            if (TIDB_BRANCH == "master") {
                GEWT_PULL_ID = extract_pull_id(GEWT_COMMIT_MSG)[0].replaceAll("#", "")
            } else {
                GEWT_PULL_ID = extract_pull_id(GEWT_COMMIT_MSG)[1].replaceAll("#", "")
            }

            echo "commit_msg=${GEWT_COMMIT_MSG}"
            echo "author=${GEWT_AUTHOR}"
            echo "author_email=${GEWT_AUTHOR_EMAIL}"
            echo "pull_id=${GEWT_PULL_ID}"
        } else {
            echo 'trigger manually'
            echo "param ref not exist"
        }

        if ( env.TIDB_COMMIT_ID == '') {
            echo "invalid param TIDB_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param TIDB_COMMIT_ID')
        }

        echo "COMMIT=${TIDB_COMMIT_ID}"
        echo "BRANCH=${TIDB_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "tidb_integration_test_ci"),
                booleanParam(name: 'release_test', value: true),
                booleanParam(name: 'update_commit_status', value: true),
                string(name: 'release_test__release_branch', value: TIDB_BRANCH),
                string(name: 'release_test__tidb_commit', value: TIDB_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    def pipeline_result = []
    def triggered_job_result = []

    try {
        stage("Build") {
            build(job: "tidb_merged_pr_build", parameters: default_params, wait: true, propagate: true)
        }
        stage("Trigger Test Job") {
            container("github-status-updater") {

                builds = [:]
                builds["tidb_ghpr_integration_cdc_test"] = {
                    def result = build(job: "tidb_ghpr_integration_cdc_test", parameters: default_params, wait: true, propagate: false)
                    triggered_job_result << ["name": "tidb_ghpr_integration_cdc_test", "type": "tidb-merge-ci-checker" , "result": result]
                    if (result.getResult() != "SUCCESS") {
                        throw new Exception("tidb_ghpr_integration_cdc_test failed")
                    }
                }
                // The following jobs on master branch are triggered by prow, so we don't need to trigger them again.
                if (TIDB_BRANCH != "master") {
                    builds["tidb_ghpr_common_test"] = {
                        def result = build(job: "tidb_ghpr_common_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_common_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_common_test failed")
                        }
                    }
                    builds["tidb_ghpr_integration_common_test"] = {
                        def result = build(job: "tidb_ghpr_integration_common_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_integration_common_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_integration_common_test failed")
                        }
                    }
                    builds["tidb_ghpr_integration_copr_test"] = {
                        def result = build(job: "tidb_ghpr_integration_copr_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_integration_copr_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_integration_copr_test failed")
                        }
                    }
                    builds["tidb_ghpr_integration_ddl_test"] = {
                        def result = build(job: "tidb_ghpr_integration_ddl_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_integration_ddl_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_integration_ddl_test failed")
                        }
                    }
                    builds["tidb_ghpr_sqllogic_test_1"] = {
                        def result = build(job: "tidb_ghpr_sqllogic_test_1", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_sqllogic_test_1", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_sqllogic_test_1 failed")
                        }
                    }
                    builds["tidb_ghpr_sqllogic_test_2"] = {
                        def result = build(job: "tidb_ghpr_sqllogic_test_2", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_sqllogic_test_2", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_sqllogic_test_2 failed")
                        }
                    }
                    builds["tidb_ghpr_tics_test"] = {
                        def result = build(job: "tidb_ghpr_tics_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_ghpr_tics_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_ghpr_tics_test failed")
                        }
                    }
                    builds["tidb_e2e_test"] = {
                        def result = build(job: "tidb_e2e_test", parameters: default_params, wait: true, propagate: false)
                        triggered_job_result << ["name": "tidb_e2e_test", "type": "tidb-merge-ci-checker" , "result": result]
                        if (result.getResult() != "SUCCESS") {
                            throw new Exception("tidb_e2e_test failed")
                        }
                    }
                }
                parallel builds
            }
        }

        currentBuild.result = "SUCCESS"
    } catch(Exception e) {
        currentBuild.result = "FAILURE"
        println "catch_exception Exception"
        println e
    } finally {
        container("golang") {
            stage("summary") {
                sh """
                wget ${FILE_SERVER_URL}/download/rd-index-agent/repo_tidb_integration_test_ci/tiinsight-agent-integration-test-ci.py
                """
                for (result_map in triggered_job_result) {
                    def name = result_map["name"]
                    def type = result_map["type"]
                    def triggered_job_summary = ""
                    if (result_map.result.getDescription() != null && result_map.result.getDescription() != "") {
                        if (name == "tidb_ghpr_coverage") {
                            println "this is tidb_ghpr_coverage"
                            triggered_job_summary = result_map.result.getDescription()
                        } else {
                            // println "description: ${result_map.result.getDescription()}"
                            def jsonObj = readJSON text: result_map.result.getDescription()
                            triggered_job_summary = parseBuildResult(jsonObj)
                            writeJSON file: "${name}.json", json: result_map.result.getDescription(), pretty: 4
                            sh """
                            python3 tiinsight-agent-integration-test-ci.py ${name} ${TIDB_COMMIT_ID} ${TIDB_BRANCH} ${name}.json
                            """
                        }
                    }
                    // println "name: ${name}, type: ${type}, result: triggered_job_summary"
                    pipeline_result << [
                        name: result_map["name"],
                        type: result_map["type"],
                        result: result_map["result"].getResult(),
                        fullDisplayName: result_map.result.getFullDisplayName(),
                        buildNumber: result_map.result.getNumber().toString(),
                        summary: triggered_job_summary,
                        durationStr: result_map.result.getDurationString(),
                        duration: result_map.result.getDuration(),
                        startTime: result_map.result.getStartTimeInMillis(),
                        url: "${CI_JENKINS_BASE_URL}/blue/organizations/jenkins/${result_map.result.getFullProjectName()}/detail/${result_map.result.getFullProjectName()}/${result_map.result.getNumber().toString()}/pipeline"
                    ]
                }

                pipeline_result << [
                    name: "tidb-merge-ci",
                    result: currentBuild.result,
                    type: "mergeci-pipeline",
                    buildNumber: BUILD_NUMBER,
                    commitID: TIDB_COMMIT_ID,
                    branch: TIDB_BRANCH,
                    prID: GEWT_PULL_ID.replaceAll("#", ""),
                    repo: "tidb",
                    org: "pingcap",
                    url: RUN_DISPLAY_URL,
                    startTime: taskStartTimeInMillis,
                    duration: System.currentTimeMillis() - taskStartTimeInMillis,
                    trigger: "tidb-merge-ci",
                ]
                println "PR ID : ${GEWT_PULL_ID.replaceAll("#", "")}"
                def notify_lark = ["purelind", GEWT_AUTHOR]
                // def notify_email = [GEWT_AUTHOR_EMAIL]
                def notify_email = []
                pipeline_result << [
                    "name": "ci-notify",
                    "type": "ci-notify",
                    "lark": notify_lark,
                    "email": notify_email,
                ]
                def json = groovy.json.JsonOutput.toJson(pipeline_result)
                writeJSON file: 'ciResult.json', json: json, pretty: 4
                sh 'cat ciResult.json'
                archiveArtifacts artifacts: 'ciResult.json', fingerprint: true
                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'GITHUB_API_TOKEN'),
                                 string(credentialsId: 'sre-bot-token', variable: 'GITHUB_API_TOKEN_CHECK_DATA'),
                                 string(credentialsId: 'break-mergeci-github-token', variable: 'BREAK_MERGE_CI_GITHUB_API_TOKEN'),
                                 string(credentialsId: 'feishu-ci-report-integration-test', variable: "FEISHU_ALERT_URL"),
                                 string(credentialsId: 'feishu-ci-report-break-tidb-integration-test', variable: "FEISHU_BREAK_IT_ALERT_URL",)
                ]) {
                    if (TIDB_BRANCH == "master") {
                        sh """
                        export LC_CTYPE="en_US.UTF-8"
                        export GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
                        export BREAK_MERGE_CI_GITHUB_API_TOKEN=${BREAK_MERGE_CI_GITHUB_API_TOKEN}
                        export COMMENT_PR_GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
                        export GITHUB_API_TOKEN_CHECK_DATA=${GITHUB_API_TOKEN}
                        rm -rf agent-tidb-mergeci.py
                        wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-tidb-mergeci.py
                        wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent_upload_tidb_mergeci_commit_status.py
                        python3 agent_upload_tidb_mergeci_commit_status.py ${TIDB_BRANCH} ${TIDB_COMMIT_ID} || true
                        python3 agent-tidb-mergeci.py ciResult.json ${FEISHU_BREAK_IT_ALERT_URL} ${FEISHU_ALERT_URL} || true
                        """
                    } else {
                        sh """
                        export LC_CTYPE="en_US.UTF-8"
                        export BREAK_MERGE_CI_GITHUB_API_TOKEN=${BREAK_MERGE_CI_GITHUB_API_TOKEN}
                        export COMMENT_PR_GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
                        rm -rf agent-tidb-mergeci.py
                        wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-tidb-mergeci.py
                        wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent_upload_tidb_mergeci_commit_status.py
                        python3 agent_upload_tidb_mergeci_commit_status.py ${TIDB_BRANCH} ${TIDB_COMMIT_ID} || true
                        python3 agent-tidb-mergeci.py ciResult.json || true
                        """
                    }
                }
            }
        }
    }
}
