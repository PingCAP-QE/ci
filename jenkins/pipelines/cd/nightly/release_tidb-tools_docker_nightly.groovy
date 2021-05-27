def BUILD_URL = 'git@github.com:pingcap/tidb-tools.git'
def slackcolor = 'good'
def githash

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

try {
    node("delivery") {
      container('delivery') {
        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        sh "rm -rf ./*"
        stage("Checkout") {
            dir("go/src/github.com/pingcap/tidb-tools") {
                // deleteDir()
                git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "master"
                sh "git checkout ${BUILD_TAG}"
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }
        stage("Build & Upload") {
          dir("go/src/github.com/pingcap/tidb-tools") {
            def wss = pwd()
            def DOCKER_TAG = "${BUILD_TAG}"
            if ( DOCKER_TAG == "master" ) {
                DOCKER_TAG = "nightly"
            }

            sh """
                cd /home/jenkins
                mkdir -p .docker
                cp /etc/dockerconfig.json .docker/config.json
                cd $wss
                mkdir -p bin
                cat - >"bin/Dockerfile" <<EOF
FROM registry-mirror.pingcap.net/library/golang:1.13.8-buster as builder
WORKDIR /go/src/github.com/pingcap/tidb-tools
COPY . .
RUN make build

FROM registry-mirror.pingcap.net/library/alpine:3.12
RUN apk add --no-cache tzdata bash curl socat
COPY --from=builder /go/src/github.com/pingcap/tidb-tools/bin/ddl_checker /tidb-tools/ddl_checker
COPY --from=builder /go/src/github.com/pingcap/tidb-tools/bin/sync_diff_inspector /tidb-tools/sync_diff_inspector
EOF
                docker build -f bin/Dockerfile -t ${DOCKER_REGISTRY}/pingcap/tidb-tools:${DOCKER_TAG} .
                docker push ${DOCKER_REGISTRY}/pingcap/tidb-tools:${DOCKER_TAG}
            """
            }
        }
      }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}