def BUILD_URL = 'git@github.com:pingcap/br.git'
def slackcolor = 'good'
def githash

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

try {
    node("delivery") {
        container('delivery') {
            sh "rm -rf ./*"
            stage("Checkout") {
                dir("go/src/github.com/pingcap/br") {
                    // deleteDir()
                    git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "master"
                    sh "git checkout ${BUILD_TAG}"
                    githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
            }
            stage("Build & Upload") {
                dir("go/src/github.com/pingcap/br") {
                    def wss = pwd()
                    def DOCKER_TAG = "${BUILD_TAG}"
                    if ( DOCKER_TAG == "master" ) {
                        DOCKER_TAG = "nightly"
                    }
                    if ( DOCKER_TAG == "release-4.0" ){
                        DOCKER_TAG = "release-4.0-nightly"
                    }
                    if ( DOCKER_TAG == "release-5.0-rc" ){
                        DOCKER_TAG = "v5.0-rc-nightly"
                    }
                    if ( DOCKER_TAG == "release-5.0" ){
                        DOCKER_TAG = "v5.0.0-nightly"
                    }
                    if ( DOCKER_TAG == "release-5.1" ){
                        DOCKER_TAG = "v5.1.0-nightly"
                    }

                    sh """
                cd /home/jenkins
                mkdir -p .docker
                cp /etc/dockerconfig.json .docker/config.json
                cd $wss
                mkdir -p bin
                cat - >"bin/Dockerfile" <<EOF
FROM registry-mirror.pingcap.net/library/golang:1.13.8-alpine3.11 as builder
RUN apk add --no-cache gcc libc-dev make bash git
WORKDIR /go/src/github.com/pingcap/br
COPY . .
RUN make build

FROM registry-mirror.pingcap.net/library/alpine:3.12
RUN apk add --no-cache tzdata bash curl socat
COPY --from=builder /go/src/github.com/pingcap/br/bin/br /br
CMD [ "/br" ]
EOF
                docker build -f bin/Dockerfile -t ${DOCKER_REGISTRY}/pingcap/br:${DOCKER_TAG} . 
                docker push ${DOCKER_REGISTRY}/pingcap/br:${DOCKER_TAG}
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