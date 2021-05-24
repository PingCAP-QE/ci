
def dm_sha1
env.DOCKER_HOST = "tcp://localhost:2375"
println "should push docker: ${PUSH_DOCKER}"
catchError {
    node('delivery') {
        container("delivery") {
            stage('Prepare') {
                def wss = pwd()
                sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p .docker
            cp /etc/dockerconfig.json .docker/config.json
            cp -R /etc/.aws ./
            cd $wss
            """
                dir ('centos7') {
                    dm_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/dm/${DM_BRANCH}/sha1").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/dm/${dm_sha1}/centos7/dm-linux-amd64.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "dm-${RELEASE_TAG}-linux-amd64"

                dir("${target}") {
                    sh "cp -R ../centos7/dm-linux-amd64/* ./"
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
            
            if (PUSH_DOCKER == "true") {
                stage('Push Docker Image') {
                    dir("dm_docker_build") {
                        sh"""
                        cp ../centos7/dm-linux-amd64/bin/dm-worker .
                        cp ../centos7/dm-linux-amd64/bin/dm-master .
                        cp ../centos7/dm-linux-amd64/bin/dmctl .
                        cp ../centos7/dm-linux-amd64/bin/mydumper .
                        cat > Dockerfile << __EOF__
FROM alpine:3.10
run apk add --no-cache tzdata
COPY dm-worker /dm-worker
COPY dm-master /dm-master
COPY dmctl /dmctl
COPY mydumper /mydumper

EXPOSE 8291 8261 8262
__EOF__
                        """
                    }
                    withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                        docker.build("pingcap/dm:${RELEASE_TAG}", "dm_docker_build").push()
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration}` Mins" + "\n" +
    "dm Branch: `${DM_BRANCH}`, Githash: `${dm_sha1.take(7)}`" + "\n" +
    "dm Binary Download URL:" + "\n" +
    "http://download.pingcap.org/dm-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
    "dm Binary sha256   URL:" + "\n" +
    "http://download.pingcap.org/dm-${RELEASE_TAG}-linux-amd64.sha256"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

