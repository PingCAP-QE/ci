/*
* @RELEASE_TAG
* @RELEASE_BRANCH
*/



env.DOCKER_HOST = "tcp://localhost:2375"

ng_monitoring_sha1 = ""
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


            }

            def os = "linux"
            def arch = "amd64"
            def platform = "centos7"
            builds["Push tidb Docker"] = {
                libs.release_online_image("tidb", tidb_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push tikv Docker"] = {
                libs.release_online_image("tikv", tikv_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push pd Docker"] = {
                libs.release_online_image("pd", pd_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push lightning Docker"] = {
                libs.release_online_image("tidb-lightning", tidb_lightning_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push br Docker"] = {
                libs.release_online_image("br", tidb_br_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push dumpling Docker"] = {
                libs.release_online_image("dumpling", dumpling_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push tidb-binlog Docker"] = {
                libs.release_online_image("tidb-binlog", tidb_binlog_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push cdc Docker"] = {
                libs.release_online_image("ticdc", cdc_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            builds["Push tiflash Docker"] = {
                libs.release_online_image("tiflash", tiflash_sha1, arch,  os , platform,RELEASE_TAG, false, false)
            }

            stage("Push tarbll/image") {
                parallel builds
            }


        }
    }

    currentBuild.result = "SUCCESS"
}
