package cd
/*
* @RELEASE_TAG
* @RELEASE_BRANCH
*/



env.DOCKER_HOST = "tcp://localhost:2375"

ng_monitoring_sha1 = ""
dm_sha1 = ""
tidb_sha1 = ""
tikv_sha1 = ""
pd_sha1 = ""

tidb_lightning_sha1=""
tidb_br_sha1=""
tidb_binlog_sha1=""

tiflash_sha1 = ""
cdc_sha1 = ""
dm_sha1 = ""
dumpling_sha1 = ""
ng_monitoring_sha1 = ""
enterprise_plugin_sha1 = ""
def libs
def taskStartTimeInMillis = System.currentTimeMillis()
try {
    catchError {
        stage('Prepare') {
            node('delivery') {
                container('delivery') {
                    dir('centos7') {
                        checkout scm
                        libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"


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
                        tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                        tidb_ctl_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
                        mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()
                    }
                }
            }
        }


        node('delivery') {
            container("delivery") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                def wss = pwd()
                sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p /root/.docker
            yes | cp /etc/dockerconfig.json /root/.docker/config.json
            yes|cp -R /etc/.aws /root
            cd $wss
            """
//                stage('download') {
//                    dir('centos7') {
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-amd64.tar.gz | tar xz"
//
//
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-amd64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
//                    }
//
//                    dir('arm') {
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-arm64.tar.gz | tar xz"
//                        // sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-arm64.tar.gz | tar xz"
//                    }
//
//                    dir('etcd') {
//                        sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
//                        sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-arm64.tar.gz | tar xz"
//                    }
//                }
                stage('publish tiup prod && publish community image') {
                    publishs = [:]
                    publishs["publish tiup prod"] = {
//                        build job: 'tiup-mirror-update-test',
//                                wait: true,
//                                parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
                        println("publish tiup prod")
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
                    println("publish enterprise image")
                    println("tidb_hash:"+tidb_sha1)
//                    build job: 'release-enterprise-docker',
//                            wait: true,
//                            parameters: [
//                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
//                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"],
//                                    [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
//                                    [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
//                                    [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
//                                    [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
//                                    [$class: 'StringParameterValue', name: 'PLUGIN_HASH', value: enterprise_plugin_sha1],
//                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: false]
//                            ]
                }
//                stage('publish tiup offline package && publish dm tiup offline package') {
//                    publishs = [:]
//                    publishs["publish tiup offline package"] = {
//                        build job: 'tiup-package-offline-mirror',
//                                wait: true,
//                                parameters: [
//                                        [$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"]
//                                ]
//                    }
//                    publishs["publish dm tiup offline package"] = {
//                        // publish dm offline package (include linux amd64 and arm64)
//                        build job: 'tiup-package-offline-mirror-dm',
//                                wait: true,
//                                parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"]]
//                    }
//                    parallel publishs
//                }
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
}



