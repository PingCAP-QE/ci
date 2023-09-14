package cd

node("pipeline_data_collection_pod") {
    container("pymysql") {
        def finally_result = [
                "pipeline_name": params.PIPELINE_NAME,
                "pipeline_type":params.PIPELINE_TYPE,
                "status":params.STATUS,
                "jenkins_build_id": params.JENKINS_BUILD_ID,
                "jenkins_run_url": params.JENKINS_RUN_URL,
                "pipeline_revoker": params.PIPELINE_REVOKER,
                "error_code": params.ERROR_CODE,
                "error_summary":params.ERROR_SUMMARY,
                "pipeline_run_start_time":params.PIPELINE_RUN_START_TIME,
                "pipeline_run_end_time":params.PIPELINE_RUN_END_TIME,
        ]
        collect_pipeline_info(finally_result)
    }
}

def collect_pipeline_info(finally_result) {
    writeJSON file: 'finally_result.json', json: finally_result, pretty: 4
    sh 'cat finally_result.json'
    archiveArtifacts artifacts: 'finally_result.json', fingerprint: true
    sh """
        export LC_CTYPE="en_US.UTF-8"
        wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent_tibuild_pipeline_run.py
    """
    result = sh(returnStdout: true, script: "python3 agent_tibuild_pipeline_run.py finally_result.json").trim()
    print(result)
}
