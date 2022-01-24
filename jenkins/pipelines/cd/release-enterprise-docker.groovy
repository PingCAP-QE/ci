/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @LIGHTNING_TAG
* @IMPORTER_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @RELEASE_TAG
*/

env.DOCKER_HOST = "tcp://localhost:2375"


def buildImage = "registry-mirror.pingcap.net/library/golang:1.14-alpine"
if (RELEASE_TAG >= "v5.2.0") {
    buildImage = "registry-mirror.pingcap.net/library/golang:1.16.4-alpine3.13"
}

echo "use ${buildImage} as build image"

catchError {
    stage('Prepare') {
        node('delivery') {
            container('delivery') {
                dir ('centos7') {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_TAG} -s=${FILE_SERVER_URL}").trim()
                    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_TAG} -s=${FILE_SERVER_URL}").trim()
                    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${BINLOG_TAG} -s=${FILE_SERVER_URL}").trim()
                    tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${TIFLASH_TAG} -s=${FILE_SERVER_URL}").trim()
                    tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TOOLS_TAG} -s=${FILE_SERVER_URL}").trim()
                    br_hash = ""
                    importer_hash = ""
                    if (RELEASE_TAG >= "v5.2.0") {
                        tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${BR_TAG} -s=${FILE_SERVER_URL}").trim()
                    } else {
                        tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${BR_TAG} -s=${FILE_SERVER_URL}").trim()
                        importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${IMPORTER_TAG} -s=${FILE_SERVER_URL}").trim()
                    }
                    cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${CDC_TAG} -s=${FILE_SERVER_URL}").trim()

                    if(RELEASE_TAG >= "v4.0.0"  && RELEASE_TAG < "v5.3.0") {
                        dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${DUMPLING_TAG} -s=${FILE_SERVER_URL}").trim()
                    }
                    if(RELEASE_TAG >= "v5.3.0") {
                        dumpling_sha1 = tidb_br_sha1
                    }
                    tidb_lightning_sha1 = tidb_br_sha1
                    tidb_ctl_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
                    mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()
                }
            }
        }
    }

    node('delivery') {
        container("delivery") {
            deleteDir()
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
                dir ('centos7') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server-${RELEASE_TAG}-enterprise.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server-${RELEASE_TAG}-enterprise.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server-${RELEASE_TAG}-enterprise.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br.tar.gz | tar xz"
                    if (RELEASE_TAG < "v5.2.0") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${RELEASE_TAG}/${importer_sha1}/centos7/importer.tar.gz | tar xz"
                    }
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"

                    if(RELEASE_TAG >= "v4.0.0") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling.tar.gz | tar xz"
                    }

                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash.tar.gz | tar xz"

                }

//                dir ('arm') {
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server-${RELEASE_TAG}-linux-arm64-enterprise.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server-${RELEASE_TAG}-linux-arm64-enterprise.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server-${RELEASE_TAG}-linux-arm64-enterprise.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-lightning/${tidb_lightning_sha1}/centos7/tidb-lightning-linux-arm64.tar.gz | tar xz"
//
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/${importer_sha1}/centos7/importer-linux-arm64.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz"
//                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz"
//
//                    if(RELEASE_TAG >= "v4.0.0") {
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/${dumpling_sha1}/centos7/dumpling-linux-arm64.tar.gz | tar xz"
//                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-arm64.tar.gz | tar xz"
//                    }
//
//                    // sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
//                }

                dir ('etcd') {
                    sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
                    sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-arm64.tar.gz | tar xz"
                }
            }


            def builds = [:]

            builds["Push tidb Docker"] = {
                dir('tidb_docker_build') {
                    sh """
                    cp ../centos7/bin/tidb-server ./
                    wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tidb/Dockerfile
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb-enterprise:${RELEASE_TAG}", "tidb_docker_build").push()
                }
            }

            builds["Push tikv Docker"] = {
                dir('tikv_docker_build') {
                    sh """
                    cp ../centos7/bin/tikv-server ./
                    cp ../centos7/bin/tikv-ctl ./
                    wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tikv/Dockerfile
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tikv-enterprise:${RELEASE_TAG}", "tikv_docker_build").push()
                }
            }

            builds["Push pd Docker"] = {
                dir('pd_docker_build') {
                    sh """
                    cp ../centos7/bin/pd-server ./
                    cp ../centos7/bin/pd-ctl ./
                    wget ${FILE_SERVER_URL}/download/script/release-dockerfile/pd/Dockerfile
                    """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/pd-enterprise:${RELEASE_TAG}", "pd_docker_build").push()
                }
            }

            builds["Push lightning Docker"] = {
                retag_enterprise_docker("tidb-lightning", RELEASE_TAG)
            }

            builds["Push tidb-binlog Docker"] = {
                retag_enterprise_docker("tidb-binlog", RELEASE_TAG)
            }

            builds["Push tiflash Docker"] = {
                retag_enterprise_docker("tiflash", RELEASE_TAG)
            }

            builds["Push cdc Docker"] = {
                retag_enterprise_docker("ticdc", RELEASE_TAG)
            }

            stage("Push tarbll/image") {
                parallel builds
            }

        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"

    if(currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
    // if (currentBuild.result != "SUCCESS") {
    //     slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // }
}
