package cd
/*
* @RELEASE_TAG
* @RELEASE_BRANCH
* @NEED_MUTIARCH
* @DEBUG_MODE
*/



env.DOCKER_HOST = "tcp://localhost:2375"

ng_monitoring_sha1 = ""
dm_sha1 = ""
tidb_sha1 = ""
tikv_sha1 = ""
pd_sha1 = ""

tidb_lightning_sha1 = ""
tidb_br_sha1 = ""
tidb_binlog_sha1 = ""

tiflash_sha1 = ""
cdc_sha1 = ""
dm_sha1 = ""
dumpling_sha1 = ""
ng_monitoring_sha1 = ""
enterprise_plugin_sha1 = ""
tidb_monitor_initializer_sha1 = ""

def libs
def taskStartTimeInMillis = System.currentTimeMillis()
begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')

try {
    catchError {
        stage('Prepare') {
            node('delivery') {
                container('delivery') {
                    dir('centos7') {
                        checkout scm
                        libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    }
                }
            }
        }


        node('delivery') {
            container("delivery") {
                stage("prepare aws key") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    def wss = pwd()
                    sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p /root/.docker
            yes | cp /etc/dockerconfig.json /root/.docker/config.json
            cd $wss
            """
                }
                if (NEED_MULTIARCH == "true") {
                    stage('publish tiup prod && publish community image && publish enterprise image') {
                        def publishs = [:]
                        publishs["publish tiup prod"] = {
                            println("start publish tiup prod")
                            build job: 'tiup-mirror-online-ga',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                            [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: DEBUG_MODE],
                                            [$class: 'StringParameterValue', name: 'TIUP_ENV', value: "prod"],
                                            [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: true],
                                            [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: true],
                                            [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: true],
                                            [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: true],

                                    ]
                        }
                        publishs["publish community image"] = {
                            build job: 'multi-arch-sync-docker',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"],
                                            [$class: 'BooleanParameterValue', name: 'IF_ENTERPRISE', value: false],
                                            [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: DEBUG_MODE],
                                    ]


                        }
                        publishs["publish enterprise image"] = {
                            build job: 'multi-arch-sync-docker',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"],
                                            [$class: 'BooleanParameterValue', name: 'IF_ENTERPRISE', value: true],
                                            [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: DEBUG_MODE],
                                    ]
                        }
                        parallel publishs
                    }
                    stage('sync enterprise image to gcr') {
                        def source = "hub.pingcap.net/enterprise/"
                        if (DEBUG_MODE == "true") {
                            source = "hub.pingcap.net/ga-debug-enterprise/"
                        }
                        def type = "ga"
                        libs.enterprise_docker_sync_gcr(source, type)
                    }
                } else {
                    stage('publish tiup prod && publish community image') {
                        def publishs = [:]
                        publishs["publish tiup prod"] = {
                            println("start publish tiup prod")
                            build job: 'tiup-mirror-update-test',
                                    wait: true,
                                    parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
                        }
                        publishs["publish community image"] = {
                            build job: 'release-community-docker',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"],
                                            [$class: 'StringParameterValue', name: 'TIDB_SHA', value: tidb_sha1],
                                            [$class: 'StringParameterValue', name: 'TIKV_SHA', value: tikv_sha1],
                                            [$class: 'StringParameterValue', name: 'PD_SHA', value: pd_sha1],
                                            [$class: 'StringParameterValue', name: 'TIDB_LIGHTNING_SHA', value: tidb_lightning_sha1],
                                            [$class: 'StringParameterValue', name: 'BR_SHA', value: tidb_br_sha1],
                                            [$class: 'StringParameterValue', name: 'DUMPLING_SHA', value: dumpling_sha1],
                                            [$class: 'StringParameterValue', name: 'TIDB_BINLOG_SHA', value: tidb_binlog_sha1],
                                            [$class: 'StringParameterValue', name: 'CDC_SHA', value: cdc_sha1],
                                            [$class: 'StringParameterValue', name: 'DM_SHA', value: dm_sha1],
                                            [$class: 'StringParameterValue', name: 'TIFLASH_SHA', value: tiflash_sha1],
                                            [$class: 'StringParameterValue', name: 'NG_MONITORING_SHA', value: ng_monitoring_sha1],
                                    ]
                        }
                        parallel publishs
                    }
                    stage('publish enterprise image') {
                        build job: 'release-enterprise-docker',
                                wait: true,
                                parameters: [
                                        [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                        [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"],
                                        [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                                        [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                                        [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                                        [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                                        [$class: 'StringParameterValue', name: 'PLUGIN_HASH', value: enterprise_plugin_sha1],
                                        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: "false"]
                                ]
                    }
                }

                stage('publish tiup offline package && publish dm tiup offline package') {
                    def publishs = [:]
                    if (RELEASE_BRANCH >= "release-6.0") {
                        publishs["publish tiup offline package"] = {
                            build job: 'tiup-package-offline-mirror-v6.0.0',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"],
                                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                                            [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: DEBUG_MODE],
                                    ]
                        }
                    } else {
                        publishs["publish tiup offline package"] = {
                            build job: 'tiup-package-offline-mirror',
                                    wait: true,
                                    parameters: [
                                            [$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"],
                                    ]
                        }
                    }
                    publishs["publish dm tiup offline package"] = {
                        // publish dm offline package (include linux amd64 and arm64)
                        build job: 'tiup-package-offline-mirror-dm',
                                wait: true,
                                parameters: [
                                        [$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"],
                                        [$class: 'BooleanParameterValue', name: 'DEBUG_MODE', value: DEBUG_MODE],
                                ]

                    }
                    parallel publishs
                }

            }
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
    getHash()
    upload_result_to_db()
}

def getHash() {
    node("delivery") {
        container("delivery") {
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
            tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            if (RELEASE_TAG >= "v5.2.0") {
                tidb_br_sha1 = tidb_sha1
            } else {
                tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }
            tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()

            if (RELEASE_TAG >= "v5.3.0") {
                dumpling_sha1 = tidb_sha1
                dm_sha1 = cdc_sha1
                ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            } else {
                dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            tidb_lightning_sha1 = tidb_br_sha1
            enterprise_plugin_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=enterprise-plugin -version=${RELEASE_BRANCH}").trim()
            tidb_monitor_initializer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=monitoring -version=master").trim()
        }
    }
}

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "11"
    pipeline_name = "GA-Build"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tidb commit:" + tidb_sha1 + ",tikv commit:" + tikv_sha1 + ",tiflash commit:" + tiflash_sha1 + ",dumpling commit:" + dumpling_sha1 + ",pd commit:" + pd_sha1 + ",tidb-binlog commit:" + tidb_binlog_sha1 + ",ticdc commit:" + cdc_sha1 + ",dm commit:" + dm_sha1 + ",br commit:" + tidb_sha1 + ",lightning commit:" + tidb_sha1 + ",tidb-monitor-initializer commit:" + tidb_monitor_initializer_sha1 + ",ng-monitoring commit:" + ng_monitoring_sha1 + ",enterprise-plugin commit:" + enterprise_plugin_sha1
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "All"
    arch = "All"
    artifact_type = "All"
    branch = RELEASE_BRANCH
    version = RELEASE_TAG
    build_type = "ga-build"
    push_gcr = "Yes"

    build job: 'upload_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value: status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value: build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value: job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value: end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value: triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value: arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value: artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type],
                    [$class: 'StringParameterValue', name: 'PUSH_GCR', value: push_gcr]
            ]

}
