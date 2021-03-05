def baseUrl = "https://raw.githubusercontent.com/PingCAP-QE/ci/jenkins-pipelines/jenkins/Dockerfile/release/"
def build_arm_image = { tag, repo ->
    def dockerUrl = baseUrl + "${repo}-arm64"
    stage("build ${repo}") {
        docker.withRegistry("", "dockerhub") {
            sh """
            docker image rm -f pingcap/${repo}:${tag}
            wget ${dockerUrl}
            docker build  -t pingcap/${repo}-arm64:${tag} -f ${repo}-arm64 .
            docker push pingcap/${repo}-arm64:${tag}
            """
        }
    }
}
//node("arm_image") {
//    dir("go/src/github.com/pingcap/monitoring") {
//        stage("prepare monitor") {
//            deleteDir()
//            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/monitoring.git']]]
//            sh """
//               go build -o pull-monitoring  cmd/monitoring.go
//               go build -o ./reload/build/linux/reload  ./reload/main.go
//            """
//            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
//                retry(3) {
//                    sh """
//                    ./pull-monitoring  --config=monitoring.yaml --tag=${RELEASE_TAG} --token=$TOKEN
//                    ls monitor-snapshot/${RELEASE_TAG}/operator
//                    """
//                }
//            }
//        }
//        stage("build monitor") {
//
//            docker.withRegistry("", "dockerhub") {
//                sh """
//                export DOCKER_HOST=unix:///var/run/docker.sock
//                cd monitor-snapshot/${RELEASE_TAG}/operator
//                docker build  -t pingcap/tidb-monitor-initializer-arm64:${RELEASE_TAG} -f Dockerfile .
//                docker push pingcap/tidb-monitor-initializer-arm64:${RELEASE_TAG}
//                """
//            }
//        }
//        stage("build reloader") {
//            docker.withRegistry("", "dockerhub") {
//                sh """
//                export DOCKER_HOST=unix:///var/run/docker.sock
//                cd reload
//                wget ${baseUrl}tidb-monitor-reloader-arm64
//                docker build  -t pingcap/tidb-monitor-reloader-arm64:v1.0.1 -f tidb-monitor-reloader-arm64 .
//                docker push pingcap/tidb-monitor-reloader-arm64:v1.0.1
//                """
//            }
//        }
//    }
//}

node("arm_image") {
    stage("prepare binary") {
        deleteDir()
        sh """
        curl http://download.pingcap.org/tiflash-${RELEASE_TAG}-linux-arm64.tar.gz | tar xz
        curl http://download.pingcap.org/tidb-toolkit-${RELEASE_TAG}-linux-arm64.tar.gz | tar xz
        curl http://download.pingcap.org/tidb-${RELEASE_TAG}-linux-arm64.tar.gz | tar xz
        curl https://tiup-mirrors.pingcap.com/cdc-${RELEASE_TAG}-linux-arm64.tar.gz | tar xz
        """
        sh """
        cp tidb-${RELEASE_TAG}-linux-arm64/bin/* .
        cp tidb-toolkit-${RELEASE_TAG}-linux-arm64/bin/* .
        cp -r tiflash-${RELEASE_TAG}-linux-arm64 tiflash
        rm tiflash/PingCAP*
        cp /usr/local/go/lib/time/zoneinfo.zip .
        """
    }
//    build_arm_image(TIDB_TAG, "tidb")
//    build_arm_image(TIKV_TAG, "tikv")
//    build_arm_image(PD_TAG, "pd")
    build_arm_image(BINLOG_TAG, "tidb-binlog")
    build_arm_image(LIGHTNING_TAG, "tidb-lightning")
    build_arm_image(BR_TAG, "br")
    build_arm_image(CDC_TAG, "ticdc")
    build_arm_image(TIFLASH_TAG, "tiflash")
    build_arm_image(DUMPLING_TAG, "dumpling")
}

stage('Summary') {
    echo "Send slack here ..."
    def slackmsg = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}"
    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
