
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
// TIFLOW_COMMIT_ID = "d660e483c2cf3df13d891a38fa29bcae53c52a08"
// TIFLOW_BRANCH = "master"




// Notify
// PR author from PingCAP will receive a notification by company email and lark.
// PR author from community will receive a notification by github email.

// title: tiflow-merge-ci #build-id

def taskStartTimeInMillis = System.currentTimeMillis()

node("github-status-updater") {
    stage("Checkout"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            println "${ref}"
            TIFLOW_BRANCH = trimPrefix(ref)
            if (TIFLOW_BRANCH == "master") {
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

        if ( env.TIFLOW_COMMIT_ID == '') {
            echo "invalid param TIFLOW_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param TIFLOW_COMMIT_ID')
        }

        echo "COMMIT=${TIFLOW_COMMIT_ID}"
        echo "BRANCH=${TIFLOW_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "tiflow_merge_ci"),
                booleanParam(name: 'release_test', value: true),
                booleanParam(name: 'update_commit_status', value: true),
                string(name: 'release_test__release_branch', value: TIFLOW_BRANCH),
                string(name: 'release_test__cdc_commit', value: TIFLOW_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    def pipeline_result = []
    def triggered_job_result = []

    try {
        stage("Build") {
            build(job: "tiflow_merged_pr_build", parameters: default_params, wait: true, propagate: true)
        }
        stage("Trigger Test Job") {
            container("github-status-updater") {
                parallel(
                        cdc_ghpr_integration_test: {
                            def result = build(job: "cdc_ghpr_integration_test", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "cdc_ghpr_integration_test", "type": "tiflow-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("cdc_ghpr_integration_test failed")
                            }
                        },
                        cdc_ghpr_kafka_integration_test: {
                            def result = build(job: "cdc_ghpr_kafka_integration_test", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "cdc_ghpr_kafka_integration_test", "type": "tiflow-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("cdc_ghpr_kafka_integration_test failed")
                            }
                        },
                        tiflow_merged_pr_ut: {
                            def result = build(job: "tiflow_merged_pr_ut", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "tiflow_merged_pr_ut", "type": "tiflow-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("tiflow_merged_pr_ut failed")
                            }
                        },
                        tiflow_merge_pr_tcms: {
                            def result = build(job: "tiflow_merge_pr_tcms", parameters: default_params, wait: true, propagate: false)
                            triggered_job_result << ["name": "tiflow_merge_pr_tcms", "type": "tiflow-merge-ci-checker" , "result": result]
                            if (result.getResult() != "SUCCESS") {
                                throw new Exception("tiflow_merge_pr_tcms failed")
                            }
                        },
                       
                )
            }
        }

        currentBuild.result = "SUCCESS"
    } catch(Exception e) {
        currentBuild.result = "FAILURE"
        println "catch_exception Exception"
        println e
    } finally {
        container("golang") { 
            println "test tiflow mergeci"
        }
    }
}