
def tidb_tools_sha1
def tidb_enterprise_tools_sha1
env.DOCKER_HOST = "tcp://localhost:2375"

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

            cd $wss
            """
                dir ('centos7') {
                    tidb_enterprise_tools_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-enterprise-tools/${TIDB_ENTERPRISE_TOOLS_BRANCH}/sha1").trim()
                    sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb-enterprise-tools/${tidb_enterprise_tools_sha1}/centos7/tidb-enterprise-tools.tar.gz | tar xz"
                    tidb_tools_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-tools/${TIDB_TOOLS_BRANCH}/sha1").trim()
                    sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz | tar xz"
                    mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/${MYDUMPER_BRANCH}/sha1").trim()
                    sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-amd64.tar.gz | tar xz && mv mydumper-linux-amd64/bin/mydumper bin/ && rm -rf mydumper-linux-amd64"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tidb-enterprise-tools-${RELEASE_TAG}-linux-amd64"

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

            }

            stage('Push tidb-enterprise-tools Docker') {
                dir('tidb_enterprise_tools_docker_build') {
                    sh  """
                cp ../centos7/bin/* ./
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc:mysql_client
COPY mydumper /mydumper
COPY sync_diff_inspector /sync_diff_inspector
__EOF__
                """
                }

                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                    docker.build("pingcap/tidb-enterprise-tools:${RELEASE_TAG}", "tidb_enterprise_tools_docker_build").push()
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
    "tidb-enterprise-tools Branch: `${TIDB_ENTERPRISE_TOOLS_BRANCH}`, Githash: `${tidb_enterprise_tools_sha1.take(7)}`" + "\n" +
    "tidb-enterprise-tools Binary Download URL:" + "\n" +
    "http://download.pingcap.org/tidb-enterprise-tools-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
    "tidb-enterprise-tools Binary sha256   URL:" + "\n" +
    "http://download.pingcap.org/tidb-enterprise-tools-${RELEASE_TAG}-linux-amd64.sha256" + "\n" +
    "tidb-enterprise-tools Docker Image: `pingcap/tidb-enterprise-tools:${RELEASE_TAG}`"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
