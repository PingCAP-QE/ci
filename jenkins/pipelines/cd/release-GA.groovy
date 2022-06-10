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
}
//                        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
//
//
//                        tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        if (RELEASE_TAG >= "v5.2.0") {
//                            tidb_br_sha1 = tidb_sha1
//                        } else {
//                            tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        }
//                        tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//
//                        if (RELEASE_TAG >= "v5.3.0") {
//                            dumpling_sha1 = tidb_sha1
//                            dm_sha1 = cdc_sha1
//                            ng_monitoring_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ng-monitoring -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        } else {
//                            dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        }
//
//                        tidb_lightning_sha1 = tidb_br_sha1
//                        tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
//                        tidb_ctl_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
//                        mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()
//stage('download') {
//    dir('centos7') {
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-amd64.tar.gz | tar xz"
//
//
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-amd64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
//    }
//
//    dir('arm') {
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${RELEASE_TAG}/${tidb_sha1}/centos7/tidb-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${RELEASE_TAG}/${tikv_sha1}/centos7/tikv-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${RELEASE_TAG}/${pd_sha1}/centos7/pd-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${RELEASE_TAG}/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${RELEASE_TAG}/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${RELEASE_TAG}/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${RELEASE_TAG}/${dumpling_sha1}/centos7/dumpling-linux-arm64.tar.gz | tar xz"
//        // sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
//        sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash-linux-arm64.tar.gz | tar xz"
//    }
//
//    dir('etcd') {
//        sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
//        sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-arm64.tar.gz | tar xz"
//    }
//}
//                stage('deprete:publish ansible'){
//                    def push_binary = { release_tag, ws ->
//                        def push_bin = { target ->
//                            dir("${target}") {
//                                sh """
//                           mkdir bin
//                           [ -f ${ws}/centos7/bin/goyacc ] && cp ${ws}/centos7/bin/goyacc ./bin
//                           cp ${ws}/centos7/bin/pd-ctl ./bin
//                           cp ${ws}/centos7/bin/pd-recover ./bin
//                           cp ${ws}/centos7/bin/pd-server ./bin
//                           cp ${ws}/centos7/bin/tidb-ctl ./bin
//                           cp ${ws}/centos7/bin/tidb-server ./bin
//                           cp ${ws}/centos7/bin/tikv-ctl ./bin
//                           cp ${ws}/centos7/bin/tikv-server ./bin
//                           cp ${ws}/etcd/etcd-v3.3.10-linux-amd64/etcdctl ./bin
//                           cp ${ws}/centos7/bin/pump ./bin
//                           cp ${ws}/centos7/bin/drainer ./bin
//                           cp ${ws}/centos7/bin/reparo ./bin
//                           cp ${ws}/centos7/bin/binlogctl ./bin
//                           cp ${ws}/centos7/bin/arbiter ./bin
//                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
//                           md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
//                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
//                           md5sum --check /tmp/chinese.check
//                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
//                           md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
//                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
//                           md5sum --check /tmp/english.check
//                        """
//                            }
//
//                            sh """
//                        tar czvf ${target}.tar.gz ${target}
//                        sha256sum ${target}.tar.gz > ${target}.sha256
//                        md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
//
//                            sh """
//                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
//                        upload.py ${target}.tar.gz ${target}.tar.gz
//                        upload.py ${target}.sha256 ${target}.sha256
//                        upload.py ${target}.md5 ${target}.md5
//                        """
//
//                            sh """
//                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
//                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
//                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
//                        """
//                        }
//
//                        def push_arm_bin = { target ->
//                            dir("${target}") {
//                                sh """
//                           mkdir bin
//                           # [ -f ${ws}/centos7/bin/goyacc ] && cp ${ws}/centos7/bin/goyacc ./bin
//                           cp ${ws}/arm/bin/pd-ctl ./bin
//                           cp ${ws}/arm/bin/pd-recover ./bin
//                           cp ${ws}/arm/bin/pd-server ./bin
//                           cp ${ws}/arm/bin/tidb-ctl ./bin
//                           cp ${ws}/arm/bin/tidb-server ./bin
//                           cp ${ws}/arm/bin/tikv-ctl ./bin
//                           cp ${ws}/arm/bin/tikv-server ./bin
//                           cp ${ws}/etcd/etcd-v3.3.10-linux-arm64/etcdctl ./bin
//                           cp ${ws}/arm/bin/pump ./bin
//                           cp ${ws}/arm/bin/drainer ./bin
//                           cp ${ws}/arm/bin/reparo ./bin
//                           cp ${ws}/arm/bin/binlogctl ./bin
//                           cp ${ws}/arm/bin/arbiter ./bin
//                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
//                           md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
//                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
//                           md5sum --check /tmp/chinese.check
//                           wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
//                           md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
//                           curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
//                           md5sum --check /tmp/english.check
//                        """
//                            }
//
//                            sh """
//                        tar czvf ${target}.tar.gz ${target}
//                        sha256sum ${target}.tar.gz > ${target}.sha256
//                        md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
//
//                            sh """
//                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
//                        upload.py ${target}.tar.gz ${target}.tar.gz
//                        upload.py ${target}.sha256 ${target}.sha256
//                        upload.py ${target}.md5 ${target}.md5
//                        """
//
//                            sh """
//                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
//                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
//                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
//                        """
//                        }
//
//                        def push_toolkit = { target ->
//                            dir("${target}") {
//                                sh """
//                           mkdir bin
//                           cp ${ws}/centos7/bin/sync_diff_inspector ./bin
//                           cp ${ws}/centos7/bin/pd-tso-bench ./bin
//                           cp ${ws}/centos7/bin/tidb-lightning ./bin
//                           cp ${ws}/centos7/bin/tidb-lightning-ctl ./bin
//                           cp ${ws}/centos7/bin/br ./bin
//                           cp ${ws}/centos7/bin/dumpling ./bin
//                           cp ${ws}/centos7/mydumper-linux-amd64/bin/mydumper ./bin
//                        """
//                            }
//
//                            sh """
//                        tar czvf ${target}.tar.gz ${target}
//                        sha256sum ${target}.tar.gz > ${target}.sha256
//                        md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
//
//
//                            sh """
//                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
//                        upload.py ${target}.tar.gz ${target}.tar.gz
//                        upload.py ${target}.sha256 ${target}.sha256
//                        upload.py ${target}.md5 ${target}.md5
//                        """
//
//                            sh """
//                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
//                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
//                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
//                        """
//                        }
//
//                        def push_arm_toolkit = { target ->
//                            dir("${target}") {
//                                sh """
//                           mkdir bin
//                           cp ${ws}/arm/bin/sync_diff_inspector ./bin
//                           cp ${ws}/arm/bin/pd-tso-bench ./bin
//                           cp ${ws}/arm/bin/br ./bin
//                           cp ${ws}/arm/bin/tidb-lightning ./bin
//                           cp ${ws}/arm/bin/tidb-lightning-ctl ./bin
//                           cp ${ws}/arm/bin/dumpling ./bin
//                           # cp ${ws}/arm/bin/mydumper ./bin
//                        """
//                            }
//
//                            sh """
//                        tar czvf ${target}.tar.gz ${target}
//                        sha256sum ${target}.tar.gz > ${target}.sha256
//                        md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo  ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
//
//                            sh """
//                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
//                        upload.py ${target}.tar.gz ${target}.tar.gz
//                        upload.py ${target}.sha256 ${target}.sha256
//                        upload.py ${target}.md5 ${target}.md5
//                        """
//
//                            sh """
//                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
//                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
//                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
//                        """
//                        }
//
//                        def push_tiflash = { target ->
//                            dir("${target}") {
//                                sh """
//                            cp -R ${ws}/centos7/tiflash/* ./
//                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
//                            md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
//                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
//                            md5sum --check /tmp/chinese.check
//                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
//                            md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
//                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
//                            md5sum --check /tmp/english.check
//                        """
//                            }
//                            sh """
//                    tar czvf ${target}.tar.gz ${target}
//                    sha256sum ${target}.tar.gz > ${target}.sha256
//                    md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
////tiflash linux amd linux version 有 release ci，不需要再次上传到公有云
//                        }
//
//                        def push_arm_tiflash = { target ->
//                            dir("${target}") {
//                                sh """
//                            cp -R ${ws}/arm/tiflash/* ./
//                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
//                            md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
//                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
//                            md5sum --check /tmp/chinese.check
//                            wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
//                            md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
//                            curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
//                            md5sum --check /tmp/english.check
//                        """
//                            }
//                            sh """
//                    tar czvf ${target}.tar.gz ${target}
//                    sha256sum ${target}.tar.gz > ${target}.sha256
//                    md5sum ${target}.tar.gz > ${target}.md5
//                    """
//
//                            def filepath = "builds/pingcap/release/${target}.tar.gz"
//                            sh """
//                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                    echo ${FILE_SERVER_URL}/download/builds/pingcap/release/${target}.tar.gz
//                    """
//
//                            sh """
//                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
//                        upload.py ${target}.tar.gz ${target}.tar.gz
//                        upload.py ${target}.sha256 ${target}.sha256
//                        upload.py ${target}.md5 ${target}.md5
//                        """
//
//                            sh """
//                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
//                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
//                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
//                        """
//                        }
//
//                        push_bin("tidb-${release_tag}-linux-amd64")
//                        push_toolkit("tidb-toolkit-${release_tag}-linux-amd64")
//                        push_arm_bin("tidb-${release_tag}-linux-arm64")
//                        push_arm_toolkit("tidb-toolkit-${release_tag}-linux-arm64")
//                        if (RELEASE_TAG >= "v5.3.0") {
//                            libs.release_dm_ansible_amd64(dm_sha1, RELEASE_TAG)
//                        }
//                        if (RELEASE_TAG >= "v3.1") {
//                            push_tiflash("tiflash-${release_tag}-linux-amd64")
//                            push_arm_tiflash("tiflash-${release_tag}-linux-arm64")
//                        }
//                    }
//                    def ws = pwd()
//                    push_binary(RELEASE_TAG, ws)
//                }




