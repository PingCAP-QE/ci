env.DOCKER_HOST = "tcp://localhost:2375"

def IMPORTER_BRANCH = "master"
def taskStartTimeInMillis = System.currentTimeMillis()
begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')
tidb_sha1 = ""
tikv_sha1 = ""
pd_sha1 = ""
tidb_binlog_sha1 = ""

def push_docker_image(item, dir_name) {
    def harbor_tmp_image_name = "hub.pingcap.net/image-sync/" + item
    def sync_dest_image_name = item

    docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
        docker.build(harbor_tmp_image_name, dir_name).push()
    }
    sync_image_params = [
            string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
            string(name: 'SOURCE_IMAGE', value: harbor_tmp_image_name),
            string(name: 'TARGET_IMAGE', value: sync_dest_image_name),
    ]
    build(job: "jenkins-image-syncer", parameters: sync_image_params, wait: true, propagate: true)
    sh "docker rmi ${harbor_tmp_image_name} || true"
}

retry(2) {
    try {
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
            cp -R /root/.aws ./
            cd $wss
            """
                    stage('Prepare') {
                        dir('centos7') {
                            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                            tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_VERSION} -s=${FILE_SERVER_URL}").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz && rm -f bin/ddltest"

                            tikv_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${TIKV_VERSION} -s=${FILE_SERVER_URL}").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"

                            pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${PD_VERSION} -s=${FILE_SERVER_URL}").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"

                            tidb_ctl_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-ctl/master/sha1").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb-ctl/${tidb_ctl_sha1}/centos7/tidb-ctl.tar.gz | tar xz"

                            def importer_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/importer/${IMPORTER_BRANCH}/sha1").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/importer/${importer_sha1}/centos7/importer.tar.gz | tar xz"

                            tidb_binlog_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${TIDB_BINLOG_VERSION} -s=${FILE_SERVER_URL}").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"

                            tidb_tools_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${TIDB_TOOLS_VERSION} -s=${FILE_SERVER_URL}").trim()
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz && rm -f bin/checker && rm -f bin/importer && rm -f bin/dump_region"
//lightning 要迁移到 br 仓库，br 打包的时候会包含 lightning ，这会导致 br 覆盖 tidb-lightning 包中的二进制。临时调整顺序来解决
// 后续等正式迁移后改造
                            if ((TIDB_BR_VERSION.startsWith("release-") && TIDB_BR_VERSION >= "release-5.2") || (TIDB_BR_VERSION == "master")) {
                                tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_BR_VERSION} -s=${FILE_SERVER_URL}").trim()
                            } else {
                                tidb_br_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${TIDB_BR_VERSION} -s=${FILE_SERVER_URL}").trim()
                            }
                            if ((TIDB_BR_VERSION.startsWith("release-") && TIDB_BR_VERSION >= "release-5.3") || (TIDB_BR_VERSION == "master")) {
                                dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${TIDB_BR_VERSION} -s=${FILE_SERVER_URL}").trim()
                            } else {
                                dumpling_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${DUMPLING_VERSION} -s=${FILE_SERVER_URL}").trim()
                            }
                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/br/${TIDB_BR_VERSION}/${tidb_br_sha1}/centos7/br.tar.gz | tar xz"

                            sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/dumpling/${dumpling_sha1}/centos7/dumpling.tar.gz | tar xz"
                        }
                        dir('etcd') {
                            sh "curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-amd64.tar.gz | tar xz"
                        }
                    }
                    stage("publish docker image") {
                        def builds = [:]
                        builds["Publish Monitor Docker Image"] = {
                            dir("monitoring_docker_build") {
                                deleteDir()
                                unstash 'monitoring'
                                dir("go/src/github.com/pingcap/monitoring") {
                                    def item = "pingcap/tidb-monitor-initializer:" + RELEASE_TAG
                                    def dir_name = "monitor-snapshot/${TIDB_VERSION}/operator"
                                    push_docker_image(item, dir_name)
                                }
                            }
                        }
                        builds["Push tidb Docker"] = {
                            dir('tidb_docker_build') {
                                sh """
                cp ../centos7/bin/tidb-server ./
                wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tidb/Dockerfile
                """
                            }
                            def item = "pingcap/tidb:" + RELEASE_TAG
                            def dir_name = "tidb_docker_build"
                            push_docker_image(item, dir_name)
                        }
                        builds["Push tikv Docker"] = {
                            dir('tikv_docker_build') {
                                sh """
                cp ../centos7/bin/tikv-server ./
                cp ../centos7/bin/tikv-ctl ./
                wget ${FILE_SERVER_URL}/download/script/release-dockerfile/tikv/Dockerfile
                """
                            }
                            def item = "pingcap/tikv:" + RELEASE_TAG
                            def dir_name = "tikv_docker_build"
                            push_docker_image(item, dir_name)
                        }
                        builds["Push pd Docker"] = {
                            dir('pd_docker_build') {
                                sh """
                cp ../centos7/bin/pd-server ./
                cp ../centos7/bin/pd-ctl ./
                wget ${FILE_SERVER_URL}/download/script/release-dockerfile/pd/Dockerfile
                """
                            }
                            def item = "pingcap/pd:" + RELEASE_TAG
                            def dir_name = "pd_docker_build"
                            push_docker_image(item, dir_name)
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
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY tidb-lightning /tidb-lightning
COPY tidb-lightning-ctl /tidb-lightning-ctl
COPY tikv-importer /tikv-importer
COPY br /br
__EOF__
                """
                            }
                            def item = "pingcap/tidb-lightning:" + RELEASE_TAG
                            def dir_name = "lightning_docker_build"
                            push_docker_image(item, dir_name)
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
                            def item = "pingcap/tidb-binlog:" + RELEASE_TAG
                            def dir_name = "tidb_binlog_docker_build"
                            push_docker_image(item, dir_name)
                        }
                        parallel builds
                    }
                }
            }

            currentBuild.result = "SUCCESS"
        }
    } catch (Exception e) {
        currentBuild.result = "FAILURE"
        echo "${e}"
        echo "retry!!!"

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
        upload_result_to_db()
    }
}

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "8"
    pipeline_name = "Nightly Image Build to Dockerhub"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "tidb commit:" + tidb_sha1 + ",tikv commit:" + tikv_sha1 + ",pd commit:" + pd_sha1 + ",tidb-binlog commit:" + tidb_binlog_sha1 + ",lightning commit:" + tidb_sha1
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "All"
    arch = "linux-amd64"
    artifact_type = "community image"
    branch = "master"
    version = "None"
    build_type = "nightly-build"
    push_gcr = "No"

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