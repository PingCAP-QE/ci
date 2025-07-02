/*
@RESULT_JOB_NAME
@RESULT_BUILD_RESULT
@RESULT_BUILD_NUMBER
@RESULT_RUN_DISPLAY_URL
@RESULT_TASK_START_TS
@SEND_TYPE
 */

if(SEND_TYPE == 'ALL' || (SEND_TYPE == 'FAILURE' && RESULT_BUILD_RESULT == 'FAILURE')) {
    def result = [:]
    result["name"] = "【" + RESULT_BUILD_RESULT + "】" + RESULT_JOB_NAME
    result["result"] = RESULT_BUILD_RESULT.toLowerCase()
    result["build_num"] = RESULT_BUILD_NUMBER
    result["type"] = "jenkinsci"
    result["url"] = RESULT_RUN_DISPLAY_URL
    result["duration"] = System.currentTimeMillis() - RESULT_TASK_START_TS.toLong()
    result["start_time"] = RESULT_TASK_START_TS.toLong()
    result["trigger"] = RESULT_JOB_NAME
    if (RESULT_BUILD_RESULT == "SUCCESS") {
        result["notify_message"] = "【" + RESULT_BUILD_RESULT + "】" + RESULT_JOB_NAME + " success"
    } else if (RESULT_BUILD_RESULT == "FAILURE") {
        result["notify_message"] = "【" + RESULT_BUILD_RESULT + "】" + RESULT_JOB_NAME + " failed"
    } else {
        result["notify_message"] = "【" + RESULT_BUILD_RESULT + "】" + RESULT_JOB_NAME + " aborted"
    }

    result["notify_receiver"] = ["heibaijian", "purelind"]

    node("lightweight_pod") {
        container("golang") {
            writeJSON file: 'result.json', json: result, pretty: 4
            sh 'cat result.json'
            archiveArtifacts artifacts: 'result.json', fingerprint: true
            sh """
            export LC_CTYPE="en_US.UTF-8"
            wget ${FILE_SERVER_URL}/download/rd-atom-agent/agent-jenkinsci.py
            python3 agent-jenkinsci.py result.json || true
        """
        }
    }
}else{
    print "SUCCESS! Not send notify!"
}
