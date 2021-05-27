def tidb_binlog_sha1
env.DOCKER_HOST = "tcp://localhost:2375"

catchError {
    node('delivery') {
        container("delivery") {
            stage('Prepare') {
                def wss = pwd()
                sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p /root/.docker
            yes | cp /etc/dockerconfig.json /root/.docker/config.json
            yes|cp -R /etc/.aws /root
            cd $wss
            """
                dir ('centos7') {
                    tidb_binlog_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-binlog/${TIDB_BINLOG_BRANCH}/sha1").trim()
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/${tidb_binlog_sha1}/centos7/tidb-binlog.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tidb-binlog-${RELEASE_TAG}-linux-amd64"

                dir("${target}") {
                    sh "cp -R ../centos7/bin ./"
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

            stage('Push tidb-binlog Docker') {
                dir('tidb_binlog_docker_build') {
                    sh  """
                cp ../centos7/bin/* ./
                cp /usr/local/go/lib/time/zoneinfo.zip ./
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc
COPY zoneinfo.zip /usr/local/go/lib/time/zoneinfo.zip
COPY pump /pump
COPY drainer /drainer
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
    def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration}` Mins" + "\n" +
    "tidb-binlog Branch: `${TIDB_BINLOG_BRANCH}`, Githash: `${tidb_binlog_sha1.take(7)}`" + "\n" +
    "tidb-binlog Binary Download URL:" + "\n" +
    "http://download.pingcap.org/tidb-binlog-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
    "tidb-binlog Binary sha256   URL:" + "\n" +
    "http://download.pingcap.org/tidb-binlog-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
    "tidb-binlog Docker Image: `pingcap/tidb-binlog:${RELEASE_TAG}`"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
