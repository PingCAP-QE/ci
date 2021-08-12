env.DOCKER_HOST = "tcp://localhost:2375"
def tidb_sha1, tikv_sha1, pd_sha1
def IMPORTER_BRANCH = "master"
catchError {
    node("${GO_BUILD_SLAVE}") {
        def ws = pwd()
        container("golang") {
            stage('Build Monitor') {
                println { NODE_NAME }
                dir("go/src/github.com/pingcap/monitoring") {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/monitoring.git']]]
                    sh """
                    # git checkout -f master
                    mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                    GOPATH=${ws}/go go build -o pull-monitoring  cmd/monitoring.go
                    """
                    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                        retry(3) {
                            sh """
                            ./pull-monitoring  --config=monitoring.yaml --tag=${TIDB_VERSION} --token=$TOKEN
                            ls monitor-snapshot/${TIDB_VERSION}/operator
                            """
                        }
                    }
                }
                stash includes: "go/src/github.com/pingcap/monitoring/**", name: "monitoring"
            }
        }
    }

    node('delivery') {
        container("delivery") {
            def wss = pwd()
            sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p .docker
            cp /etc/dockerconfig.json .docker/config.json
            cp -R /etc/.aws ./
            cd $wss
            """
            stage('Prepare') {
                dir('centos7') {
                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                    tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz && rm -f bin/ddltest"

                    tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"

                    pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"

                    tidb_ctl_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-ctl/master/sha1").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/${tidb_ctl_sha1}/centos7/tidb-ctl.tar.gz | tar xz"

                    def importer_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/importer/${IMPORTER_BRANCH}/sha1").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/importer/${importer_sha1}/centos7/importer.tar.gz | tar xz"

                    tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${TIDB_BINLOG_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"

                    tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TIDB_TOOLS_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//lightning 要迁移到 br 仓库，br 打包的时候会包含 lightning ，这会导致 br 覆盖 tidb-lightning 包中的二进制。临时调整顺序来解决
// 后续等正式迁移后改造
                    if ((TIDB_BR_VERSION.startsWith("release-") && TIDB_BR_VERSION >= "release-5.2") || (TIDB_BR_VERSION == "master")) {
                        tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_BR_VERSION} -s=${FILE_SERVER_URL}").trim()
                    } else {
                        tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${TIDB_BR_VERSION} -s=${FILE_SERVER_URL}").trim()
                    }
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/br/${TIDB_BR_VERSION}/${tidb_br_sha1}/centos7/br.tar.gz | tar xz"
                    dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${DUMPLING_VERSION} -s=${FILE_SERVER_URL}").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/${dumpling_sha1}/centos7/dumpling.tar.gz | tar xz"
                }


                dir('etcd') {
                    sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def ws = pwd()

                def push_binary = { release_tag ->
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
                            """
                        }

                        sh """
                            tar czvf ${target}.tar.gz ${target}
                            sha256sum ${target}.tar.gz > ${target}.sha256
                            md5sum ${target}.tar.gz > ${target}.md5
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
                }
                push_binary(RELEASE_TAG)
            }
            stage("Publish Monitor Docker Image") {
                dir("monitoring_docker_build") {
                    deleteDir()
                    unstash 'monitoring'
                    dir("go/src/github.com/pingcap/monitoring") {
                        withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                            docker.build("pingcap/tidb-monitor-initializer:${RELEASE_TAG}", "monitor-snapshot/${TIDB_VERSION}/operator").push()
                        }
                    }
                }
            }

            stage('Push tidb Docker') {
                dir('tidb_docker_build') {
                    sh """
                cp ../centos7/bin/tidb-server ./
                wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tidb/Dockerfile
                """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb:${RELEASE_TAG}", "tidb_docker_build").push()
                }
            }

            stage('Push tikv Docker') {
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
            }

            stage('Push pd Docker') {
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
            }

            stage('Push lightning Docker') {
                dir('lightning_docker_build') {
                    sh """
                cp ../centos7/bin/tidb-lightning ./
                cp ../centos7/bin/tidb-lightning-ctl ./
                cp ../centos7/bin/tikv-importer ./
                cp ../centos7/bin/br ./
                cp /usr/local/go/lib/time/zoneinfo.zip ./
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc
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
            }

            stage('Push tidb-binlog Docker') {
                dir('tidb_binlog_docker_build') {
                    sh """
                cp ../centos7/bin/pump ./
                cp ../centos7/bin/drainer ./
                cp ../centos7/bin/reparo ./
                cp ../centos7/bin/binlogctl ./
                cp /usr/local/go/lib/time/zoneinfo.zip ./
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc
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
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[${env.JOB_NAME.replaceAll('%2F', '/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration}` Mins" + "\n" +
            "tidb Version: `${TIDB_VERSION}`, Githash: `${tidb_sha1.take(7)}`" + "\n" +
            "tikv Version: `${TIKV_VERSION}`, Githash: `${tikv_sha1.take(7)}`" + "\n" +
            "pd   Version: `${PD_VERSION}`, Githash: `${pd_sha1.take(7)}`" + "\n" +
            "tidb-lightning   Version: `${TIDB_LIGHTNING_VERSION}`, Githash: `${tidb_br_sha1.take(7)}`" + "\n" +
            "tidb_binlog   Version: `${TIDB_BINLOG_VERSION}`, Githash: `${tidb_binlog_sha1.take(7)}`" + "\n" +
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

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"

    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: slackmsg
    }


    def slackmsg_build = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"
    if (currentBuild.result != "SUCCESS" && (branch == "master" || branch.startsWith("release") || branch.startsWith("refs/tags/v"))) {
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg_build}"
    }

}
