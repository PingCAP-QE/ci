/*
** @RELEASE_TAG
*/

def taskStartTimeInMillis = System.currentTimeMillis()
def RELEASE_BRANCH = "master"

def OS_LINUX = "linux"
def OS_DARWIN = "darwin"
def ARM64 = "arm64"
def AMD64 = "amd64"
def PLATFORM_CENTOS = "centos7"
def PLATFORM_DARWIN = "darwin"
def PLATFORM_DARWINARM = "darwin-arm64"


def FORCE_REBUILD = false

begin_time = new Date().format('yyyy-mm-dd hh:mm:ss')
tidb_sha1=""
tikv_sha1=""
pd_sha1=""
tidb_binlog_sha1=""
tidb_tools_sha1=""
cdc_sha1=""
dm_sha1=""
tiflash_sha1=""
tidb_ctl_githash=""
ng_monitoring_sha1=""




retry(2) {
    try {
        timeout(600) {
            RELEASE_TAG = "v6.2.0-alpha"
            node("build_go1130") {
                container("golang") {
                    def ws = pwd()
                    deleteDir()

                    stage("Prepare") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        println "${ws}"
                    }

                    stage("Get hash") {
                        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                        tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        dm_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                        ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -source=github -version=main -s=${FILE_SERVER_URL}").trim()
                        println "tidb hash: ${tidb_sha1}\ntikv hash: ${tikv_sha1}\npd hash: ${pd_sha1}\ntiflash hash:${tiflash_sha1}"
                        println "tiflow hash:${cdc_sha1}\ntidb-ctl hash:${tidb_ctl_githash}\nng_monitoring hash:${ng_monitoring_sha1}"

                        sh """
                echo ${tidb_sha1} > sha1
                curl --fail -F refs/pingcap/tidb/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tikv_sha1} > sha1
                curl --fail -F refs/pingcap/tikv/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${pd_sha1} > sha1
                curl --fail -F refs/pingcap/pd/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tidb_binlog_sha1} > sha1
                curl --fail -F refs/pingcap/tidb-binlog/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tidb_tools_sha1} > sha1
                curl --fail -F refs/pingcap/tidb-tools/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tidb_sha1} > sha1
                curl --fail -F refs/pingcap/br/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tidb_sha1} > sha1
                curl --fail -F refs/pingcap/dumpling/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tiflash_sha1} > sha1
                curl --fail -F refs/pingcap/tics/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${cdc_sha1} > sha1
                curl --fail -F refs/pingcap/tiflow/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${tidb_ctl_githash} > sha1
                curl --fail -F refs/pingcap/tidb-ctl/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                
                echo ${cdc_sha1} > sha1
                curl --fail -F refs/pingcap/dm/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

                echo ${ng_monitoring_sha1} > sha1
                curl --fail -F refs/pingcap/ng-monitoring/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
                """
                    }
                }
            }

            stage("Build") {
                def builds = [:]
                builds["Build on linux/arm64"] = {
                    build job: "optimization-build-tidb",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                    [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                    [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
                                    [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                    [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'OS', value: OS_LINUX],
                                    [$class: 'StringParameterValue', name: 'ARCH', value: ARM64],
                                    [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_CENTOS],
                            ]
                }

                builds["Build on darwin/amd64"] = {
                    build job: "optimization-build-tidb",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                    [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                    [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
                                    [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                    [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'OS', value: OS_DARWIN],
                                    [$class: 'StringParameterValue', name: 'ARCH', value: AMD64],
                                    [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_DARWIN],
                            ]
                }
                builds["Build on darwin/arm64"] = {
                    build job: "optimization-build-tidb",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                    [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                    [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
                                    [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                    [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'OS', value: OS_DARWIN],
                                    [$class: 'StringParameterValue', name: 'ARCH', value: ARM64],
                                    [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_DARWINARM],
                            ]
                }

                builds["Build on linux/amd64"] = {
                    build job: "optimization-build-tidb",
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                    [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                    [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                    [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                    [$class: 'StringParameterValue', name: 'DM_HASH', value: dm_sha1],
                                    [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                    [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                    [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                    [$class: 'StringParameterValue', name: 'OS', value: OS_LINUX],
                                    [$class: 'StringParameterValue', name: 'ARCH', value: AMD64],
                                    [$class: 'StringParameterValue', name: 'PLATFORM', value: PLATFORM_CENTOS],
                            ]
                }


                parallel builds
            }

            RELEASE_TAG = "nightly"

            stage("TiUP build") {
                build job: "tiup-mirror-online-ga",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'StringParameterValue', name: 'TIUP_ENV', value: "prod"],
                        ]
            }

            stage("Tiup nightly test") {
                build job: "tiup-mirror-test",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'TIUP_MIRRORS', value: TIUP_MIRRORS],
                                [$class: 'StringParameterValue', name: 'VERSION', value: RELEASE_TAG],
                        ]
            }

            currentBuild.result = "SUCCESS"
        }
    } catch (Exception e) {
        currentBuild.result = "FAILURE"
        echo "${e}"
        echo "retry!!!"
    } finally {
        send_notify(taskStartTimeInMillis)
        upload_result_to_db()
    }
}

def send_notify(long taskStartTimeInMillis) {
    def result = [:]
    result["name"] = "【" + currentBuild.result + "】" + JOB_NAME
    result["result"] = currentBuild.result.toLowerCase()
    result["build_num"] = BUILD_NUMBER
    result["type"] = "jenkinsci"
    result["url"] = RUN_DISPLAY_URL
    result["duration"] = System.currentTimeMillis() - taskStartTimeInMillis
    result["start_time"] = taskStartTimeInMillis
    result["trigger"] = "tiup nightly build"
    if (currentBuild.result == "SUCCESS") {
        result["notify_message"] = "【SUCCESS】TiUP release tidb master nightly success"
    } else if (currentBuild.result == "FAILURE") {
        result["notify_message"] = "【FAILURE】TiUP release tidb master nightly failed"
    } else {
        result["notify_message"] = "【ABORTED】TiUP release tidb master nightly aborted"
    }

    result["notify_receiver"] = ["purelind", "heibaijian", "derekstrong"]

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
}

def upload_result_to_db() {
    pipeline_build_id= params.PIPELINE_BUILD_ID
    pipeline_id= "9"
    pipeline_name= "Nightly TiUP Build"
    status= currentBuild.result
    build_number= BUILD_NUMBER
    job_name= JOB_NAME
    artifact_meta= "tidb commit:" + tidb_sha1 + ",tikv commit:" + tikv_sha1 + ",pd commit:" + pd_sha1 + ",tidb-binlog commit:" + tidb_binlog_sha1 + ",tidb-tools commit:" + tidb_tools_sha1+"ticdc commit:" + cdc_sha1 + ",dm commit:" + dm_sha1 + ",br commit:" + tidb_sha1 + ",lightning commit:" + tidb_sha1 + ",tidb-ctl commit:" + tidb_ctl_githash + ",ng-monitoring commit:" + ng_monitoring_sha1
    begin_time= begin_time
    end_time= new Date().format('yyyy-mm-dd hh:mm:ss')
    triggered_by= "sre-bot"
    component= "All"
    arch= "All"
    artifact_type= "TiUP online mirror"
    branch= "master"
    version= "Nightly"
    build_type= "nightly-build"

    build job: 'save_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value:  pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value:  status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value:  build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value:  job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value:  end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value:  triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value:  arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value:  artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type]
            ]

}
