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

                    tidb_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_TAG} -s=${FILE_SERVER_URL}").trim()
                    tikv_githash = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_TAG} -s=${FILE_SERVER_URL}").trim()
                    pd_githash = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_TAG} -s=${FILE_SERVER_URL}").trim()
                    binlog_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${BINLOG_TAG} -s=${FILE_SERVER_URL}").trim()
                    lightning_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-lightning -version=${LIGHTNING_TAG} -s=${FILE_SERVER_URL}").trim()
                    importer_githash = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${IMPORTER_TAG} -s=${FILE_SERVER_URL}").trim()
                    tools_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TOOLS_TAG} -s=${FILE_SERVER_URL}").trim()
                    br_githash = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${BR_TAG} -s=${FILE_SERVER_URL}").trim()
                    dumpling_githash = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${DUMPLING_TAG} -s=${FILE_SERVER_URL}").trim()
                    tiflash_githash = sh(returnStdout: true, script: "python gethash.py -repo=tiflash -version=${TIFLASH_TAG} -s=${FILE_SERVER_URL}").trim()
                    cdc_githash = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${CDC_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_ctl_githash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
                    dm_githash = sh(returnStdout: true, script: "python gethash.py -repo=dm -version=${DM_TAG} -s=${FILE_SERVER_URL}").trim()

                    sh """
                echo ${tidb_githash} > sha1
                curl -F refs/pingcap/tidb/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tikv_githash} > sha1
                curl -F refs/pingcap/tikv/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${pd_githash} > sha1
                curl -F refs/pingcap/pd/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${binlog_githash} > sha1
                curl -F refs/pingcap/tidb-binlog/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${lightning_githash} > sha1
                curl -F refs/pingcap/tidb-lightning/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${importer_githash} > sha1
                curl -F refs/pingcap/importer/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tools_githash} > sha1
                curl -F refs/pingcap/tidb-tools/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${br_githash} > sha1
                curl -F refs/pingcap/br/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${dumpling_githash} > sha1
                curl -F refs/pingcap/dumpling/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tiflash_githash} > sha1
                curl -F refs/pingcap/tics/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${cdc_githash} > sha1
                curl -F refs/pingcap/ticdc/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload

                echo ${tidb_ctl_githash} > sha1
                curl -F refs/pingcap/tidb-ctl/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload
                
                echo ${dm_githash} > sha1
                curl -F refs/pingcap/dm/nightly/sha1=@sha1 ${FILE_SERVER_URL}/upload
                """
                }
            }
        }

        stage("Build") {
            def builds = [:]

            builds["Build binarys linux/arm64"] = {
                build job: "build-linux-arm64-4.0",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                        ]
            }

            builds["Build binarys darwin/amd64"] = {
                build job: "build-darwin-amd64-4.0",
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                        ]
            }

            parallel builds
        }

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
}

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}