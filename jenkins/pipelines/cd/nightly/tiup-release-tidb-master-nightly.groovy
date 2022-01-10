/*
** @TIDB_TAG
** @TIKV_TAG
** @PD_TAG
** @BINLOG_TAG
** @LIGHTNING_TAG
** @IMPORTER_TAG
** @TOOLS_TAG
** @BR_TAG
** @DUMPLING_TAG
** @TIFLASH_TAG
** @CDC_TAG
** @DM_TAG
** @RELEASE_TAG
*/
def slackcolor = 'good'
def githash
def tidb_githash, tikv_githash, pd_githash, importer_githash, tools_githash
def br_githash, dumpling_githash, tiflash_githash, tidb_ctl_githash, binlog_githash
def cdc_githash, lightning_githash

def taskStartTimeInMillis = System.currentTimeMillis()

try {
    timeout(600) {
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

                    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_TAG} -s=${FILE_SERVER_URL}").trim()
                    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_TAG} -s=${FILE_SERVER_URL}").trim()
                    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${BINLOG_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TOOLS_TAG} -s=${FILE_SERVER_URL}").trim()
                    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflash -version=${TIFLASH_TAG} -s=${FILE_SERVER_URL}").trim()
                    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tiflow -version=${CDC_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
                    ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -version=main -s=${FILE_SERVER_URL}").trim()

                    sh """
                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/tidb/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tikv_sha1} > sha1
                curl -F refs/pingcap/tikv/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${pd_sha1} > sha1
                curl -F refs/pingcap/pd/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_binlog_sha1} > sha1
                curl -F refs/pingcap/tidb-binlog/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_tools_sha1} > sha1
                curl -F refs/pingcap/tidb-tools/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/br/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_sha1} > sha1
                curl -F refs/pingcap/dumpling/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tiflash_sha1} > sha1
                curl -F refs/pingcap/tics/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${cdc_sha1} > sha1
                curl -F refs/pingcap/tiflow/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_ctl_githash} > sha1
                curl -F refs/pingcap/tidb-ctl/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload
                
                echo ${cdc_sha1} > sha1
                curl -F refs/pingcap/dm/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${ng_monitoring_sha1} > sha1
                curl -F refs/pingcap/ng-monitoring/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload
                """
                }
            }
        }

        RELEASE_TAG = "v5.5.0-alpha"

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
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
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
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
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
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
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
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                [$class: 'StringParameterValue', name: 'NGMonitoring_HASH', value: ng_monitoring_sha1],
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
