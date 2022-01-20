/*
** @RELEASE_TAG
*/
def slackcolor = 'good'
def githash
def tidb_githash, tikv_githash, pd_githash, importer_githash, tools_githash
def br_githash, dumpling_githash, tiflash_githash, tidb_ctl_githash, binlog_githash
def cdc_githash, lightning_githash

def taskStartTimeInMillis = System.currentTimeMillis()
def RELEASE_BRANCH = "master"

def FORCE_REBUILD = false

try {
    timeout(600) {
        RELEASE_TAG = "v5.5.0-alpha"
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

                    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflash -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -source=github -version=${RELEASE_BRANCH} -s=${FILE_SERVER_URL}").trim()
                    ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -source=github -version=main -s=${FILE_SERVER_URL}").trim()

                    sh """
                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/tidb/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tikv_sha1} > sha1
                curl -F refs/pingcap/tikv/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${pd_sha1} > sha1
                curl -F refs/pingcap/pd/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_binlog_sha1} > sha1
                curl -F refs/pingcap/tidb-binlog/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_tools_sha1} > sha1
                curl -F refs/pingcap/tidb-tools/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/br/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/dumpling/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tiflash_sha1} > sha1
                curl -F refs/pingcap/tics/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${cdc_sha1} > sha1
                curl -F refs/pingcap/tiflow/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_ctl_githash} > sha1
                curl -F refs/pingcap/tidb-ctl/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload
                
                echo ${cdc_sha1} > sha1
                curl -F refs/pingcap/dm/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${ng_monitoring_sha1} > sha1
                curl -F refs/pingcap/ng-monitoring/${RELEASE_TAG}/sha1=@sha1 ${FILE_SERVER_URL}/upload
                """
                }
            }
        }

        stage("Build") {
            def builds = [:]
            builds["Build on linux/arm64"] = {
                build job: "optimization-build-tidb-linux-arm",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                        ]
            }

            builds["Build on darwin/amd64"] = {
                build job: "optimization-build-tidb-darwin-amd",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                        ]
            }
            builds["Build on darwin/arm64"] = {
                build job: "optimization-build-tidb-darwin-arm",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                        ]
            }

            builds["Build on linux/amd64"] = {
                build job: "optimization-build-tidb-linux-amd",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                                [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                                [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                                [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: tidb_sha1],
                                [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
                                [$class: 'StringParameterValue', name: 'TIDB_CTL_HASH', value: tidb_ctl_githash],
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                        ]
            }
            

            parallel builds
        }

        RELEASE_TAG = "nightly"

        stage("TiUP build") {
            build job: "tiup-mirror-update-test",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
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
    slackcolor = 'danger'
    echo "${e}"
} finally {
    def result = [:]
    result["name"] = JOB_NAME
    result["result"] = currentBuild.result.toLowerCase()
    result["build_num"] = BUILD_NUMBER
    result["type"] = "jenkinsci"
    result["url"] = RUN_DISPLAY_URL
    result["duration"] = System.currentTimeMillis() - taskStartTimeInMillis
    result["start_time"] = taskStartTimeInMillis
    result["trigger"] = "tiup nightly build"
    if (currentBuild.result == "SUCCESS") {
        result["notify_message"] = "TiUP release tidb master nightly success"
    } else if (currentBuild.result == "FAILURE") {
        result["notify_message"] = "TiUP release tidb master nightly failed"
    } else {
        result["notify_message"] = "TiUP release tidb master nightly aborted"
    }
    
    result["notify_receiver"] = ["zhouqiang-cl", "purelind", "VelocityLight"]

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

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
