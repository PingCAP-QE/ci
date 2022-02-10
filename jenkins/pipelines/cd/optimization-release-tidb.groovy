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
                        importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
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
                    if (RELEASE_TAG < "v5.2.0") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${RELEASE_TAG}/${importer_sha1}/centos7/importer-linux-amd64.tar.gz | tar xz"
                    }
                    

                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-amd64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-amd64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
                }

                dir('arm') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
                    if (RELEASE_TAG < "v5.2.0") {
                        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${RELEASE_TAG}/${importer_sha1}/centos7/importer-linux-arm64.tar.gz | tar xz"
                    } 
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

            def push_binary = { release_tag, ws ->
                def push_bin = { target ->
                    dir("${target}") {
                        sh """
                           mkdir bin
                           [ -f ${ws}/centos7/bin/goyacc ] && cp ${ws}/centos7/bin/goyacc ./bin
                           cp ${ws}/centos7/bin/pd-ctl ./bin
                           cp ${ws}/centos7/bin/pd-recover ./bin
                           cp ${ws}/centos7/bin/pd-server ./bin
                           cp ${ws}/centos7/bin/tidb-ctl ./bin
                           cp ${ws}/centos7/bin/tidb-server ./bin
                           cp ${ws}/centos7/bin/tikv-ctl ./bin
                           cp ${ws}/centos7/bin/tikv-server ./bin
                           cp ${ws}/etcd/etcd-v3.3.10-linux-amd64/etcdctl ./bin
                           cp ${ws}/centos7/bin/pump ./bin
                           cp ${ws}/centos7/bin/drainer ./bin
                           cp ${ws}/centos7/bin/reparo ./bin
                           cp ${ws}/centos7/bin/binlogctl ./bin
                           cp ${ws}/centos7/bin/arbiter ./bin

                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
                           md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
                           md5sum --check /tmp/chinese.check

                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
                           md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
                           md5sum --check /tmp/english.check
                        """
                    }

                    sh """
                        tar czvf ${target}.tar.gz ${target}
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """

                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        """

                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                }

                def push_arm_bin = { target ->
                    dir("${target}") {
                        sh """
                           mkdir bin
                           # [ -f ${ws}/centos7/bin/goyacc ] && cp ${ws}/centos7/bin/goyacc ./bin
                           cp ${ws}/arm/pd-v*-linux-arm64/bin/pd-ctl ./bin
                           cp ${ws}/arm/pd-v*-linux-arm64/bin/pd-recover ./bin
                           cp ${ws}/arm/pd-v*-linux-arm64/bin/pd-server ./bin
                           cp ${ws}/arm/tidb-ctl-v*-linux-arm64/bin/tidb-ctl ./bin
                           cp ${ws}/arm/tidb-v*-linux-arm64/bin/tidb-server ./bin
                           cp ${ws}/arm/tikv-v*-linux-arm64/bin/tikv-ctl ./bin
                           cp ${ws}/arm/tikv-v*-linux-arm64/bin/tikv-server ./bin
                           cp ${ws}/etcd/etcd-v3.3.10-linux-arm64/etcdctl ./bin
                           cp ${ws}/arm/tidb-binlog-v*-linux-arm64/bin/pump ./bin
                           cp ${ws}/arm/tidb-binlog-v*-linux-arm64/bin/drainer ./bin
                           cp ${ws}/arm/tidb-binlog-v*-linux-arm64/bin/reparo ./bin
                           cp ${ws}/arm/tidb-binlog-v*-linux-arm64/bin/binlogctl ./bin
                           cp ${ws}/arm/tidb-binlog-v*-linux-arm64/bin/arbiter ./bin

                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
                           md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
                           md5sum --check /tmp/chinese.check

                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
                           md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
                           md5sum --check /tmp/english.check
                        """
                    }

                    sh """
                        tar czvf ${target}.tar.gz ${target}
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """

                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        """

                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                }

                def push_toolkit = { target ->
                    dir("${target}") {
                        sh """
                           mkdir bin
                           cp ${ws}/centos7/bin/sync_diff_inspector ./bin
                           cp ${ws}/centos7/bin/pd-tso-bench ./bin
                           cp ${ws}/centos7/bin/tidb-lightning ./bin
                           cp ${ws}/centos7/bin/tidb-lightning-ctl ./bin
                           if [ ${RELEASE_TAG} \\< "v5.2.0" ]; then
                              cp ${ws}/centos7/bin/tikv-importer ./bin
                           fi;
                           cp ${ws}/centos7/bin/br ./bin
                           cp ${ws}/centos7/bin/dumpling ./bin
                           cp ${ws}/centos7/mydumper-linux-amd64/bin/mydumper ./bin
                        """
                    }

                    sh """
                        tar czvf ${target}.tar.gz ${target}
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """


                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        """

                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                }

                def push_arm_toolkit = { target ->
                    dir("${target}") {
                        sh """
                           mkdir bin
                           cp ${ws}/arm/tidb-tools-v*-linux-arm64/bin/sync_diff_inspector ./bin
                           cp ${ws}/arm/pd-v*-linux-arm64/bin/pd-tso-bench ./bin
                           if [ ${RELEASE_TAG} \\< "v5.2.0" ]; then
                               cp ${ws}/arm/importer-v*-linux-arm64/bin/tikv-importer ./bin
                           fi;
                           cp ${ws}/arm/br-v*-linux-arm64/bin/br ./bin
                           cp ${ws}/arm/br-v*-linux-arm64/bin/tidb-lightning ./bin
                           cp ${ws}/arm/br-v*-linux-arm64/bin/tidb-lightning-ctl ./bin
                           cp ${ws}/arm/dumpling-v*-linux-arm64/bin/dumpling ./bin
                           # cp ${ws}/arm/mydumper-linux-amd64/bin/mydumper ./bin
                        """
                    }

                    sh """
                        tar czvf ${target}.tar.gz ${target}
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """

                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        """

                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                }

                def push_tiflash = { target ->
                    dir("${target}") {
                        sh """
                            cp -R ${ws}/centos7/tiflash/* ./
                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
                            md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
                            md5sum --check /tmp/chinese.check

                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
                            md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
                            md5sum --check /tmp/english.check
                        """
                    }
                    sh """
                    tar czvf ${target}.tar.gz ${target}
                    sha256sum ${target}.tar.gz > ${target}.sha256
                    md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """
//tiflash linux amd linux version 有 release ci，不需要再次上传到公有云
                }

                def push_arm_tiflash = { target ->
                    dir("${target}") {
                        sh """
                            cp -R ${ws}/arm/tiflash-v*-linux-arm64/* ./
                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
                            md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
                            md5sum --check /tmp/chinese.check

                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
                            md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
                            md5sum --check /tmp/english.check
                        """
                    }
                    sh """
                    tar czvf ${target}.tar.gz ${target}
                    sha256sum ${target}.tar.gz > ${target}.sha256
                    md5sum ${target}.tar.gz > ${target}.md5
                    """

                    def filepath = "builds/pingcap/release/${target}.tar.gz"
                    sh """
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    echo ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
                    """

                    sh """
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        """

                    sh """
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                }

                push_bin("tidb-${release_tag}-linux-amd64")
                push_toolkit("tidb-toolkit-${release_tag}-linux-amd64")
                push_arm_bin("tidb-${release_tag}-linux-arm64")
                push_arm_toolkit("tidb-toolkit-${release_tag}-linux-arm64")
                if (RELEASE_TAG >= "v3.1") {
                    push_tiflash("tiflash-${release_tag}-linux-amd64")
                    push_arm_tiflash("tiflash-${release_tag}-linux-arm64")
                }
            }

            def builds = [:]
            builds["Push Centos7 Binary"] = {
                def ws = pwd()
                push_binary(RELEASE_TAG, ws)
            }
            def os = "linux"
            def arch = "amd64"
            def platform = "centos7"
            builds["Push tidb Docker"] = {
                libs.release_online_image("tidb", tidb_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push tikv Docker"] = {
                libs.release_online_image("tikv", tikv_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push pd Docker"] = {
                libs.release_online_image("pd", pd_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            // TODO: refine it when no longer need lightning
            builds["Push lightning Docker"] = {
                dir('lightning_docker_build') {
                    sh """
                        cp ../centos7/bin/tidb-lightning ./
                        cp ../centos7/bin/tidb-lightning-ctl ./
                        cp ../centos7/bin/br ./
                        if [ ${RELEASE_TAG} \\< "v5.2.0" ]; then 
                            cp ../centos7/bin/tikv-importer ./
                        fi;
                        cp /usr/local/go/lib/time/zoneinfo.zip ./
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc:alpine-3.14
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY tidb-lightning /tidb-lightning
COPY tidb-lightning-ctl /tidb-lightning-ctl
COPY br /br
__EOF__
                        if [ ${RELEASE_TAG} \\< "v5.2.0" ]; then
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc:alpine-3.14
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY tidb-lightning /tidb-lightning
COPY tidb-lightning-ctl /tidb-lightning-ctl
COPY tikv-importer /tikv-importer
COPY br /br
__EOF__
                        fi;
                        """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb-lightning:${RELEASE_TAG}", "lightning_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/tidb-lightning:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb-lightning:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tidb-lightning:${RELEASE_TAG}
                    """
                }
            }

            builds["Push br Docker"] = {
                libs.release_online_image("br", br_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push dumpling Docker"] = {
                libs.release_online_image("dumpling", dumpling_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push tidb-binlog Docker"] = {
                libs.release_online_image("tidb-binlog", tidb_binlog_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push cdc Docker"] = {
                libs.release_online_image("cdc", cdc_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["Push tiflash Docker"] = {
                libs.release_online_image("tiflash", tiflash_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            builds["NG Monitoring Docker"] = {
                libs.release_online_image("ng-monitoring", ng_monitoring_sha1, arch,  os , platform,RELEASE_TAG, false)
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

            builds["Publish tiup"] = {
                build job: 'tiup-mirror-update-test',
                        wait: true,
                        parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
            }

            stage("Push tarbll/image") {
                parallel builds
            }

            def build_arms = [:]
            os = "linux"
            arch = "arm64"
            platform = "centos7"

            build_arms["Push tidb Docker"] = {
                libs.release_online_image("tidb", tidb_sha1, arch, os , platform, RELEASE_TAG, false)
            }

            build_arms["Push tikv Docker"] = {
                libs.release_online_image("tikv", tikv_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push pd Docker"] = {
                libs.release_online_image("pd", pd_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push br Docker"] = {
                libs.release_online_image("br", br_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push dumpling Docker"] = {
                libs.release_online_image("dumpling", dumpling_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push tidb-binlog Docker"] = {
                libs.release_online_image("tidb-binlog", tidb_binlog_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push cdc Docker"] = {
                libs.release_online_image("cdc", cdc_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Push tiflash Docker"] = {
                libs.release_online_image("tiflash", tiflash_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["Lightning Docker"] = {
                libs.release_online_image("tidb-lightning", tidb_lightning_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            build_arms["NG Monitoring Docker"] = {
                libs.release_online_image("ng-monitoring", ng_monitoring_sha1, arch,  os , platform,RELEASE_TAG, false)
            }

            stage("Push arm images") {
                parallel build_arms
            }

            stage("Publish arm64 docker images") {
                build job: 'build-arm-image',
                        wait: true,
                        parameters: [
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"]
                        ]          
            }
        }
    }

    currentBuild.result = "SUCCESS"
}
