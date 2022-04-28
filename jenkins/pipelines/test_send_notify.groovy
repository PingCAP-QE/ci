
/*
* @RELEASE_TAG
* @RELEASE_BRANCH
*/



env.DOCKER_HOST = "tcp://localhost:2375"

catchError {
    currentBuild.result = "FAILURE"


    node('delivery') {
        container("delivery") {

            stage("Push arm images") {
                echo 'test1'
            }

            stage("Publish arm64 docker images") {
                echo 'test2'
            }
        }

        currentBuild.result = "SUCCESS"

        post {
            always {
                build job: 'send_notify',
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RESULT_JOB_NAME', value: "${JOB_NAME}"],
                                [$class: 'StringParameterValue', name: 'RESULT_BUILD_RESULT', value: currentBuild.result],
                                [$class: 'StringParameterValue', name: 'RESULT_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                                [$class: 'StringParameterValue', name: 'RESULT_RUN_DISPLAY_URL', value: "${RUN_DISPLAY_URL}"],
                                [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"]
                        ]
            }
        }
    }
}