/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @IMPORTER_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @DUMPLING_TAG
* @MINOR_RELEASE_TAG
* @RELEASE_TAG
* @RELEASE_LATEST
* @SKIP_TIFLASH
* @STAGE
* @FORCE_REBUILD
* @TIKV_PRID
*/

def get_hash = { hash_or_branch, repo ->
    if (hash_or_branch.length() == 40) {
        return hash_or_branch
    }
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${hash_or_branch} -s=${FILE_SERVER_URL}").trim()
}

def BUILD_TIKV_IMPORTER = "false"
env.DOCKER_HOST = "tcp://localhost:2375"


catchError {
    stage('Prepare') {
        node('delivery') {
            container('delivery') {
                dir('centos7') {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    if (STAGE != "build") {
                        if (TIDB_TAG.length() == 40 || TIKV_TAG.length() == 40 || PD_TAG.length() == 40 || BINLOG_TAG.length() == 40 || TIFLASH_TAG.length() == 40 || IMPORTER_TAG.length() == 40 || TOOLS_TAG.length() == 40 || BR_TAG.length() == 40 || CDC_TAG.length() == 40) {
                            println "release must be used with tag."
                            sh "exit 2"
                        }
                    }
                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                    if (STAGE == "build") {
                        tidb_sha1 = get_hash(TIDB_TAG, "tidb")
                        tikv_sha1 = get_hash(TIKV_TAG, "tikv")
                        pd_sha1 = get_hash(PD_TAG, "pd")
                        tidb_br_sha1 = get_hash(BR_TAG, "br")
                        tidb_binlog_sha1 = get_hash(BINLOG_TAG, "tidb-binlog")
                        tiflash_sha1 = get_hash(TIFLASH_TAG, "tics")
                        tidb_tools_sha1 = get_hash(TOOLS_TAG, "tidb-tools")
                        importer_sha1 = get_hash(IMPORTER_TAG, "importer")
                        cdc_sha1 = get_hash(CDC_TAG, "ticdc")
                        dumpling_sha1 = get_hash(DUMPLING_TAG, "dumpling")
                    } else {
                        tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_TAG} -s=${FILE_SERVER_URL}").trim()
                        tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_TAG} -s=${FILE_SERVER_URL}").trim()
                        pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_TAG} -s=${FILE_SERVER_URL}").trim()
                        tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${BR_TAG} -s=${FILE_SERVER_URL}").trim()
                        tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${BINLOG_TAG} -s=${FILE_SERVER_URL}").trim()
                        tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${TIFLASH_TAG} -s=${FILE_SERVER_URL}").trim()
                        tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TOOLS_TAG} -s=${FILE_SERVER_URL}").trim()
                        importer_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${IMPORTER_TAG} -s=${FILE_SERVER_URL}").trim()
                        cdc_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${CDC_TAG} -s=${FILE_SERVER_URL}").trim()
                        dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${DUMPLING_TAG} -s=${FILE_SERVER_URL}").trim()
//                        考虑到 tikv 和 importer 的 bump version，release stage 只编译 tikv 和 importer
                        BUILD_TIKV_IMPORTER = "true"
                    }
                    // lightning 从 4.0.12 开始和 br 的 hash 一样
                    tidb_lightning_sha1 = tidb_br_sha1
                    tidb_ctl_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
                    mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()
                }
            }
        }
    }

    stage('Build') {

        builds = [:]
        builds["Build on linux/arm64"] = {
            build job: "optimization-build-tidb-linux-arm",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: tidb_lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: SKIP_TIFLASH],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: BUILD_TIKV_IMPORTER],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
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
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: tidb_lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: SKIP_TIFLASH],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: BUILD_TIKV_IMPORTER],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
        def build_linux_amd = {
            build job: "optimization-build-tidb-linux-amd",
                    wait: true,
                    parameters: [
                            [$class: 'StringParameterValue', name: 'TIDB_HASH', value: tidb_sha1],
                            [$class: 'StringParameterValue', name: 'TIKV_HASH', value: tikv_sha1],
                            [$class: 'StringParameterValue', name: 'PD_HASH', value: pd_sha1],
                            [$class: 'StringParameterValue', name: 'BINLOG_HASH', value: tidb_binlog_sha1],
                            [$class: 'StringParameterValue', name: 'LIGHTNING_HASH', value: tidb_lightning_sha1],
                            [$class: 'StringParameterValue', name: 'IMPORTER_HASH', value: importer_sha1],
                            [$class: 'StringParameterValue', name: 'TOOLS_HASH', value: tidb_tools_sha1],
                            [$class: 'StringParameterValue', name: 'CDC_HASH', value: cdc_sha1],
                            [$class: 'StringParameterValue', name: 'BR_HASH', value: tidb_br_sha1],
                            [$class: 'StringParameterValue', name: 'DUMPLING_HASH', value: dumpling_sha1],
                            [$class: 'StringParameterValue', name: 'TIFLASH_HASH', value: tiflash_sha1],
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: RELEASE_TAG],
                            [$class: 'BooleanParameterValue', name: 'SKIP_TIFLASH', value: SKIP_TIFLASH],
                            [$class: 'BooleanParameterValue', name: 'BUILD_TIKV_IMPORTER', value: BUILD_TIKV_IMPORTER],
                            [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: RELEASE_BRANCH],
                            [$class: 'StringParameterValue', name: 'TIKV_PRID', value: TIKV_PRID],
                            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
                    ]
        }
        if (STAGE == "build") {
            builds["Build on linux/amd64"] = build_linux_amd
            parallel builds
        }
    }
//    build stage return
    if (STAGE == "build") {
        return
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
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${tidb_ctl_sha1}/centos7/tidb-ctl.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${importer_sha1}/centos7/importer.tar.gz | tar xz"

                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${dumpling_sha1}/centos7/dumpling.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz"
                }

                dir('arm') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${tidb_sha1}/centos7/tidb-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${tikv_sha1}/centos7/tikv-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${pd_sha1}/centos7/pd-server-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/optimization/${tidb_ctl_sha1}/centos7/tidb-ctl-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${importer_sha1}/centos7/importer-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${tidb_tools_sha1}/centos7/tidb-tools-linux-arm64.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${tidb_binlog_sha1}/centos7/tidb-binlog-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${tidb_br_sha1}/centos7/br-linux-arm64.tar.gz | tar xz"
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/optimization/${dumpling_sha1}/centos7/dumpling-linux-arm64.tar.gz | tar xz"
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
                           cp ${ws}/centos7/bin/tikv-importer ./bin
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
                           cp ${ws}/arm/importer-v*-linux-arm64/bin/tikv-importer ./bin
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

                // if the parameter MINOR_RELEASE_TAG is setting, will push minor release tag binary
                if (MINOR_RELEASE_TAG != "" && MINOR_RELEASE_TAG != null && MINOR_RELEASE_TAG != RELEASE_TAG) {
                    push_binary(MINOR_RELEASE_TAG)
                }
            }

            builds["Push tidb Docker"] = {
                dir('tidb_docker_build') {
                    sh """
                        cp ../centos7/bin/tidb-server ./
                        wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tidb/Dockerfile
                        """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb:${RELEASE_TAG}", "tidb_docker_build").push()
                }

                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/tidb:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tidb:${RELEASE_TAG}
                    """
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
                    docker.build("pingcap/tikv:${RELEASE_TAG}", "tikv_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/tikv:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tikv:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tikv:${RELEASE_TAG}
                    """
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
                    docker.build("pingcap/pd:${RELEASE_TAG}", "pd_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/pd:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/pd:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/pd:${RELEASE_TAG}
                    """
                }
            }

            builds["Push lightning Docker"] = {
                dir('lightning_docker_build') {
                    sh """
                        cp ../centos7/bin/tidb-lightning ./
                        cp ../centos7/bin/tidb-lightning-ctl ./
                        cp ../centos7/bin/tikv-importer ./
                        cp ../centos7/bin/br ./
                        cp /usr/local/go/lib/time/zoneinfo.zip ./
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY tidb-lightning /tidb-lightning
COPY tidb-lightning-ctl /tidb-lightning-ctl
COPY tikv-importer /tikv-importer
COPY br /br
__EOF__
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
                dir('br_docker_build') {
                    sh """
                        cp ../centos7/bin/br ./
                        cp /usr/local/go/lib/time/zoneinfo.zip ./
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY br /br
__EOF__
                        """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/br:${RELEASE_TAG}", "br_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/br:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/br:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/br:${RELEASE_TAG}
                    """
                }
            }

            builds["Push dumpling Docker"] = {
                dir('dumpling_docker_build') {
                    sh """
                        cp ../centos7/bin/dumpling ./
                        cp /usr/local/go/lib/time/zoneinfo.zip ./
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY dumpling /dumpling
__EOF__
                        """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/dumpling:${RELEASE_TAG}", "dumpling_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/dumpling:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/dumpling:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/dumpling:${RELEASE_TAG}
                    """
                }
            }

            builds["Push tidb-binlog Docker"] = {
                dir('tidb_binlog_docker_build') {
                    sh """
                        cp ../centos7/bin/pump ./
                        cp ../centos7/bin/drainer ./
                        cp ../centos7/bin/reparo ./
                        cp ../centos7/bin/binlogctl ./
                        cp /usr/local/go/lib/time/zoneinfo.zip ./
                        cat > Dockerfile << __EOF__
FROM pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY pump /pump
COPY drainer /drainer
COPY reparo /reparo
COPY binlogctl /binlogctl
EXPOSE 4000
EXPOSE 8249 8250
CMD ["/pump"]
__EOF__
                        """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb-binlog:${RELEASE_TAG}", "tidb_binlog_docker_build").push()
                }
                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker tag pingcap/tidb-binlog:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb-binlog:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tidb-binlog:${RELEASE_TAG}
                    """
                }
            }
//ticdc 编译，制作镜像，push
            builds["Push cdc Docker"] = {
                build job: 'release_cdc_docker',
                        wait: true,
                        parameters: [[$class: 'StringParameterValue', name: 'BUILD_TAG', value: "${RELEASE_TAG}"]]

                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker pull pingcap/ticdc:${RELEASE_TAG}
                        docker tag pingcap/ticdc:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/ticdc:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/ticdc:${RELEASE_TAG}
                    """
                }
            }
            // tiflash 上传二进制，制作上传镜像 
            builds["Push tiflash Docker"] = {
                build job: 'release_tiflash_by_tag',
                        wait: true,
                        parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]

                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker pull pingcap/tiflash:${RELEASE_TAG}
                        docker tag pingcap/tiflash:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tiflash:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tiflash:${RELEASE_TAG}
                    """
                }
            }

            builds["Push monitor initializer"] = {
                build job: 'release-monitor',
                        wait: true,
                        parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]

                docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                    sh """
                        docker pull pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                        docker tag pingcap/tidb-monitor-initializer:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                        docker push uhub.service.ucloud.cn/pingcap/tidb-monitor-initializer:${RELEASE_TAG}
                    """
                }
            }

            builds["Publish tiup"] = {
                build job: 'tiup-mirror-update-test',
                        wait: true,
                        parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
            }
            
            // 这一步显得没有必要
            // builds["Push monitor reloader"] = {
            //     docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
            //         sh """
            //             docker pull pingcap/tidb-monitor-reloader:v1.0.1
            //             docker tag pingcap/tidb-monitor-reloader:v1.0.1 uhub.service.ucloud.cn/pingcap/tidb-monitor-reloader:v1.0.1
            //             docker push uhub.service.ucloud.cn/pingcap/tidb-monitor-reloader:v1.0.1
            //         """
            //     }
            // }

            stage("Push tarbll/image") {
                parallel builds
            }

// monitoring 编译，upload
            // stage("trigger release monitor") {
            //     build job: 'release-monitor',
            //             wait: true,
            //             parameters: [[$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]]
            // }

            stage("Trigger jira version") {
                build(job: "jira_create_release_version", wait: false, parameters: [string(name: "RELEASE_TAG", value: "${RELEASE_TAG}")])
            }

            // stage("trigger release tidb on tiup") {
            //     build job: 'tiup-mirror-update-test',
            //             wait: true,
            //             parameters: [
            //                     [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"]
            //             ]
            // }
            // stage("build ucloud image") {
            //     build job: 'build-ucloud-image',
            //             wait: true,
            //             parameters: [
            //                     [$class: 'StringParameterValue', name: 'TIDB_TAG', value: TIDB_TAG],
            //                     [$class: 'StringParameterValue', name: 'TIKV_TAG', value: TIKV_TAG],
            //                     [$class: 'StringParameterValue', name: 'PD_TAG', value: PD_TAG],
            //                     [$class: 'StringParameterValue', name: 'BINLOG_TAG', value: BINLOG_TAG],
            //                     [$class: 'StringParameterValue', name: 'LIGHTNING_TAG', value: BR_TAG],
            //                     [$class: 'StringParameterValue', name: 'BR_TAG', value: BR_TAG],
            //                     [$class: 'StringParameterValue', name: 'CDC_TAG', value: CDC_TAG],
            //                     [$class: 'StringParameterValue', name: 'TIFLASH_TAG', value: TIFLASH_TAG],
            //                     [$class: 'StringParameterValue', name: 'DUMPLING_TAG', value: DUMPLING_TAG],
            //             ]

            // }
// 从 https://download.pingcap.org 下载和上传 latest 标志的包
            if (RELEASE_LATEST == "true") {
                stage('Publish Latest') {
                    build job: 'release_tidb_latest', wait: true, parameters: [
                            [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                            [$class: 'BooleanParameterValue', name: 'SKIP_DARWIN', value: "true"],
                    ]
                }
            }

        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[${env.JOB_NAME.replaceAll('%2F', '/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration}` Mins" + "\n" +
            "tidb Version: `${RELEASE_TAG}`, Githash: `${tidb_sha1.take(7)}`" + "\n" +
            "tikv Version: `${RELEASE_TAG}`, Githash: `${tikv_sha1.take(7)}`" + "\n" +
            "pd   Version: `${RELEASE_TAG}`, Githash: `${pd_sha1.take(7)}`" + "\n" +
            "tidb-lightning   Version: `${RELEASE_TAG}`, Githash: `${tidb_lightning_sha1.take(7)}`" + "\n" +
            "tidb_binlog   Version: `${RELEASE_TAG}`, Githash: `${tidb_binlog_sha1.take(7)}`" + "\n" +
            "TiDB Binary Download URL:" + "\n" +
            "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
            "http://download.pingcap.org/tidb-toolkit-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
            "TiDB Binary sha256   URL:" + "\n" +
            "http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
            "http://download.pingcap.org/tidb-toolkit-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
            "tidb Docker Image: `pingcap/tidb:${RELEASE_TAG}`" + "\n" +
            "pd   Docker Image: `pingcap/pd:${RELEASE_TAG}`" + "\n" +
            "tikv Docker Image: `pingcap/tikv:${RELEASE_TAG}`" + "\n" +
            "tidb-lightning Docker Image: `pingcap/tidb-lightning:${RELEASE_TAG}`" + "\n" +
            "tidb-binlog Docker Image: `pingcap/tidb-binlog:${RELEASE_TAG}`"

    if (currentBuild.result == "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
    // if (currentBuild.result != "SUCCESS") {
    //     slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // }
}
