/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @LIGHTNING_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @RELEASE_TAG
*/
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PLUGIN_HASH',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BINLOG_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'LIGHTNING_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'IMPORTER_TAG',
                        description: '访问 https://api.github.com/repos/tikv/importer/git/refs/tags/{TIKV_IMPORTER_VERSION}  确认版本信息正确',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TOOLS_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'DUMPLING_TAG',
                        description: '',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CDC_TAG',
                        description: '',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD',
                        description: ''
                ),
        ])
])


def libs

def os = "linux"
def platform = "centos7"
def taskStartTimeInMillis = System.currentTimeMillis()

try {
    catchError {
        stage('Prepare') {
            node('delivery') {
                container('delivery') {
                    dir('centos7') {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        checkout scm
                        libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                    }
                }
            }
        }

        node('delivery') {
            container("delivery") {
                def arch_amd64 = "amd64"
                libs.parallel_enterprise_docker(arch_amd64, true)

            }
        }

        node('arm') {
            def arch_arm64 = "arm64"
            libs.parallel_enterprise_docker(arch_arm64, true)
        }

        currentBuild.result = "SUCCESS"
    }
} catch (Exception e) {
    currentBuild.result = "FAILURE"
} finally {
    build job: 'send_notify',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'RESULT_JOB_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_RESULT', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'RESULT_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'RESULT_RUN_DISPLAY_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'RESULT_TASK_START_TS', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'SEND_TYPE', value: "ALL"]
            ]
}
