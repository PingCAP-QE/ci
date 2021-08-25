def BUILD_URL = 'git@github.com:pingcap/ticdc.git'
def slackcolor = 'good'
def githash

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def buildImage = "registry-mirror.pingcap.net/library/golang:1.14-alpine"
def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], BUILD_TAG)
if (isNeedGo1160) {
    println "This build use go1.16"
    buildImage = "registry-mirror.pingcap.net/library/golang:1.16.4-alpine3.13"
} else {
    println "This build use go1.14"
}
println "build image =${buildImage}"

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

try {
    node("delivery") {
      container('delivery') {
        sh "rm -rf ./*"
        stage("Checkout") {
            dir("go/src/github.com/pingcap/ticdc") {
                // deleteDir()
                git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "master"
                sh "git checkout ${BUILD_TAG}"
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }
        stage("Build & Upload") {
          dir("go/src/github.com/pingcap/ticdc") {
            def wss = pwd()
            def DOCKER_TAG = "${BUILD_TAG}"
            if ( DOCKER_TAG == "master" ) {
                DOCKER_TAG = "nightly"
            }
            if ( DOCKER_TAG == "release-4.0" ) {
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
FROM ${buildImage} as builder
RUN apk add --no-cache git make bash
WORKDIR /go/src/github.com/pingcap/ticdc
COPY . .
RUN make

FROM registry-mirror.pingcap.net/library/alpine:3.12
RUN apk add --no-cache tzdata bash curl socat
COPY --from=builder /go/src/github.com/pingcap/ticdc/bin/cdc /cdc
EXPOSE 8300
CMD [ "/cdc" ]
EOF
                docker build -f bin/Dockerfile -t ${DOCKER_REGISTRY}/pingcap/ticdc:${DOCKER_TAG} . 
                docker push ${DOCKER_REGISTRY}/pingcap/ticdc:${DOCKER_TAG}
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