package cd

properties([
        parameters([
                string(
                        defaultValue: '-1',
                        name: 'PIPELINE_BUILD_ID',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PIPELINE_ID',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PIPELINE_NAME',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'STATUS',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BUILD_NUMBER',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'JOB_NAME',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ARTIFACT_META',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BEGIN_TIME',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'END_TIME',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TRIGGERED_BY',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'COMPONENT',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ARCH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'ARTIFACT_TYPE',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BRANCH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'VERSION',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BUILD_TYPE',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PUSH_GCR',
                        description: '',
                        trim: true
                ),
        ])
])

node("pipeline_data_collection_pod") {
    container("pymysql") {
        def finally_result = [
                "pipeline_build_id": params.PIPELINE_BUILD_ID.toLong(),
                "pipeline_id":params.PIPELINE_ID.toLong(),
                "pipeline_name":params.PIPELINE_NAME,
                "status": params.STATUS,
                "build_number": params.BUILD_NUMBER,
                "job_name": params.JOB_NAME,
                "artifact_meta": params.ARTIFACT_META,
                "begin_time":params.BEGIN_TIME,
                "end_time":params.END_TIME,
                "triggered_by":params.TRIGGERED_BY,
                "component":params.COMPONENT,
                "arch":params.ARCH,
                "artifact_type":params.ARTIFACT_TYPE,
                "branch":params.BRANCH,
                "version": params.VERSION,
                "build_type": params.BUILD_TYPE,
                "push_gcr":param.PUSH_GCR
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
        wget http://fileserver.pingcap.net/download/builds/pingcap/ee/save_pipeline_result.py
        pwd
        ls -l
    """
    result = sh(returnStdout: true, script: "python3 save_pipeline_result.py finally_result.json").trim()
    print(result)
}
