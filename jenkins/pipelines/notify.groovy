def send_notify(jobname, job_result, build_number, run_dispaly_url, task_start_ts) {
    def result = [:]

    result["name"] = jobname
    result["result"] = job_result.toLowerCase()
    result["build_num"] = build_number
    result["type"] = "jenkinsci"
    result["url"] = run_dispaly_url
    result["duration"] = System.currentTimeMillis() - task_start_ts
    result["start_time"] = task_start_ts
    result["trigger"] = "tiup nightly build"
    if (job_result == "SUCCESS") {
        result["notify_message"] = JOB_NAME + " success"
    } else if (currentBuild.result == "FAILURE") {
        result["notify_message"] = JOB_NAME + " failed"
    } else {
        result["notify_message"] = JOB_NAME + " aborted"
    }

    result["notify_receiver"] = ["purelind", "heibaijian"]

    node("lightweight_pod") {
        container("golang") {
            writeJSON file: 'result.json', json: result, pretty: 4
            sh 'cat result.json'
            archiveArtifacts artifacts: 'result.json', fingerprint: true
            sh """
                wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-jenkinsci.py
                python3 agent-jenkinsci.py result.json || true
            """
        }
    }
}