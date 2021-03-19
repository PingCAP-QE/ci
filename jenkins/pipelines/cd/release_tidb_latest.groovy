/*
* @RELEASE_TAG string, target release tag used for publish as latest
* @PUBLISH_BINARY bool, publish tidb/tidb-toolkit amd64 binary if set
* @PUBLISH_DOCKER bool, publish tidb/tikv/pd/tidb-binlog/tidb-lightning amd64 docker if set
* @PUBLISH_DARWIN bool, publish tidb/tikv/pd darwin binary if set
*/

env.DOCKER_HOST = "tcp://localhost:2375"
def DOWNLOAD_PATH = "https://download.pingcap.org"

catchError {
    node('delivery') {
        container("delivery") {
            def ws = pwd()
            deleteDir()

            if ("$SKIP_BINARY" != "true") {
                stage("Publish Binary") {
                    def release_binary = { name, target ->
                        sh """
                        wget ${DOWNLOAD_PATH}/${name}.tar.gz
                        cp ${name}.tar.gz ${target}.tar.gz
                        sha256sum ${target}.tar.gz > ${target}.sha256
                        md5sum ${target}.tar.gz > ${target}.md5
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.sha256 ${target}.sha256
                        upload.py ${target}.md5 ${target}.md5
                        aws s3 cp ${target}.tar.gz s3://download.pingcap.org/${target}.tar.gz --acl public-read
                        aws s3 cp ${target}.sha256 s3://download.pingcap.org/${target}.sha256 --acl public-read
                        aws s3 cp ${target}.md5 s3://download.pingcap.org/${target}.md5 --acl public-read
                        """
                    }
                    sh "yes|cp -R /etc/.aws /root"
                    release_binary("tidb-${RELEASE_TAG}-linux-amd64", "tidb-latest-linux-amd64")
                    release_binary("tidb-toolkit-${RELEASE_TAG}-linux-amd64", "tidb-toolkit-latest-linux-amd64")

                    // add for linux/arm64
                    release_binary("tidb-${RELEASE_TAG}-linux-arm64", "tidb-latest-linux-arm64")
                    release_binary("tidb-toolkit-${RELEASE_TAG}-linux-arm64", "tidb-toolkit-latest-linux-arm64")
                }
            }

            if ("$SKIP_DOCKER" != "true") {
                stage("Publish Docker") {
                    def release_docker = { name ->
                        dir("docker_builder") {
                            deleteDir()
                            sh """
                            cat > Dockerfile << __EOF__
FROM ${name}:${RELEASE_TAG}
__EOF__
                            """
                        }
                        withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                            docker.build("${name}:latest", "docker_builder").push()
                        }
                    }

                    release_docker("pingcap/tidb")
                    release_docker("pingcap/tikv")
                    release_docker("pingcap/pd")
                    release_docker("pingcap/tidb-lightning")
                    release_docker("pingcap/tidb-binlog")
                }
            }

            if ("$SKIP_DARWIN" != "true") {
                stage("Publish Darwin binary") {
                    def release_binary = { name, target ->
                        sh """
                        wget ${DOWNLOAD_PATH}/${name}.tar.gz
                        cp ${name}.tar.gz ${target}.tar.gz
                        md5sum ${target}.tar.gz > ${target}.md5
                        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
                        upload.py ${target}.tar.gz ${target}.tar.gz
                        upload.py ${target}.md5 ${target}.md5
                        """
                    }
                    release_binary("tidb-${RELEASE_TAG}-darwin-amd64", "tidb-latest-darwin-amd64")
                    release_binary("pd-${RELEASE_TAG}-darwin-amd64", "pd-latest-darwin-amd64")
                    release_binary("tikv-${RELEASE_TAG}-darwin-amd64", "tikv-latest-darwin-amd64")
                }
            }

            currentBuild.result = "SUCCESS"
        }
    }
}


stage('Summary') {
    if (currentBuild.result != "SUCCESS") {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
                "Elapsed Time: `${duration}` Mins" + "\n"
        slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}