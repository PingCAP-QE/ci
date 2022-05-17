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
tiflash_sha1 = ""
cdc_sha1 = ""
dm_sha1 = ""
dumpling_sha1 = ""
ng_monitoring_sha1 = ""
enterprise_plugin_sha1 = ""
def libs
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
            stage('download') {
                dir('centos7') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-amd64.tar.gz | tar xz"


                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-amd64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
                }

                dir('arm') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-arm64.tar.gz | tar xz"
                    // sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-arm64.tar.gz | tar xz"
                }

                dir('etcd') {
                    sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
                    sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-arm64.tar.gz | tar xz"
                }
            }
            stage('publish tiup prod && publish community image') {
                publishs = [:]
                publishs["publish tiup prod"] = {
                    build job: 'tiup-mirror-update-test',
                            wait: true,
                            parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
                }
                publishs["publish community image amd64"] = {
                    community_docker_image_amd64(libs)
                }
                publishs["publish community image arm64"] = {
                    community_docker_image_arm64(libs)
                    build job: 'build-arm-image',
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                    [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"]
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
                                [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: false]
                        ]
            }
            stage('publish tiup offline package && publish dm tiup offline package') {
                publishs = [:]
                publishs["publish tiup offline package"] = {
                    build job: 'tiup-package-offline-mirror',
                            wait: true,
                            parameters: [
                                    [$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"]
                            ]
                }
                publishs["publish dm tiup offline package"] = {
                    // publish dm offline package (include linux amd64 and arm64)
                    build job: 'tiup-package-offline-mirror-dm',
                            wait: true,
                            parameters: [[$class: 'StringParameterValue', name: 'VERSION', value: "${RELEASE_TAG}"]]
                }
                parallel publishs
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

def community_docker_image_amd64(libs) {
    def builds = [:]

    def os = "linux"
    def arch = "amd64"
    def platform = "centos7"
    builds["Push tidb Docker"] = {
        libs.release_online_image("tidb", tidb_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push tikv Docker"] = {
        libs.release_online_image("tikv", tikv_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push pd Docker"] = {
        libs.release_online_image("pd", pd_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push lightning Docker"] = {
        libs.release_online_image("tidb-lightning", tidb_lightning_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push br Docker"] = {
        libs.release_online_image("br", tidb_br_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push dumpling Docker"] = {
        libs.release_online_image("dumpling", dumpling_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push tidb-binlog Docker"] = {
        libs.release_online_image("tidb-binlog", tidb_binlog_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push cdc Docker"] = {
        libs.release_online_image("ticdc", cdc_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }
    if (RELEASE_TAG >= "v5.3.0") {
        builds["Push dm Docker"] = {
            libs.release_online_image("dm", dm_sha1, arch, os, platform, RELEASE_TAG, false, false)
        }
    }
    builds["Push tiflash Docker"] = {
        libs.release_online_image("tiflash", tiflash_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["NG Monitoring Docker"] = {
        libs.release_online_image("ng-monitoring", ng_monitoring_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    // TODO: refine monitoring
    builds["Push monitor initializer"] = {
        build job: 'release-monitor',
                wait: true,
                parameters: [
                        [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                        [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"]
                ]

        docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
            sh """
                        docker pull registry-mirror.pingcap.net/pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                        docker tag registry-mirror.pingcap.net/pingcap/tidb-monitor-initializer:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                    """
        }
    }
    parallel builds
}

def community_docker_image_arm64(libs) {
    def build_arms = [:]
    def os = "linux"
    def arch = "arm64"
    def platform = "centos7"

    build_arms["arm64 tidb Docker"] = {
        libs.release_online_image("tidb", tidb_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 tikv Docker"] = {
        libs.release_online_image("tikv", tikv_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 pd Docker"] = {
        libs.release_online_image("pd", pd_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 br Docker"] = {
        libs.release_online_image("br", tidb_br_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 dumpling Docker"] = {
        libs.release_online_image("dumpling", dumpling_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 tidb-binlog Docker"] = {
        libs.release_online_image("tidb-binlog", tidb_binlog_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 ticdc Docker"] = {
        libs.release_online_image("ticdc", cdc_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }
    if (RELEASE_TAG >= "v5.3.0") {
        build_arms["arm64 dm Docker"] = {
            libs.release_online_image("dm", dm_sha1, arch, os, platform, RELEASE_TAG, false, false)
        }
    }
    build_arms["arm64 tiflash Docker"] = {
        libs.release_online_image("tiflash", tiflash_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 Lightning Docker"] = {
        libs.release_online_image("tidb-lightning", tidb_lightning_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    build_arms["arm64 NG Monitoring Docker"] = {
        libs.release_online_image("ng-monitoring", ng_monitoring_sha1, arch, os, platform, RELEASE_TAG, false, false)
    }

    parallel build_arms
}



