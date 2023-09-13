pipelineJob('send_notify') {
    description('''
使用方法：
def taskStartTimeInMillis = System.currentTimeMillis()
try {

}catch(Exception e){
    currentBuild.result = "Failure"
}finally{
    build job: 'send_notify',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'RESULT_JOB_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_RESULT', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'RESULT_RUN_DISPLAY_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'SEND_TYPE', value: "FAILURE"],
            ]
}''')
    definition {
        cpsScm {
            lightweight(true)
            scriptPath('jenkins/pipelines/cd/atom-jobs/send_notify.groovy')
            scm {
                git{
                    remote {
                        url('https://github.com/PingCAP-QE/ci.git')
                    }
                    branch('main')
                    extensions {
                        cloneOptions {
                            depth(1)
                            shallow(true)
                            timeout(5)
                        }
                    }
                }
            }
        }
    }
    parameters {
        stringParam {
            name('RESULT_JOB_NAME')
            trim(true)
        }
        stringParam {
            name('RESULT_BUILD_RESULT')
            trim(true)
        }
        stringParam {
            name('RESULT_BUILD_NUMBER')
            trim(true)
        }
        stringParam {
            name('RESULT_RUN_DISPLAY_URL')
            trim(true)
        }
        stringParam {
            name('RESULT_TASK_START_TS')
            trim(true)
        }
        stringParam {
            name('SEND_TYPE')
            defaultValue('ALL')
            trim(false)
        }
    }
}
