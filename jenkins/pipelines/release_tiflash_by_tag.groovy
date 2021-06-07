env.DOCKER_HOST = "tcp://localhost:2375"

catchError {
    node('delivery') {
        container("delivery") {
            stage('Download') {
                def wss = pwd()
                sh """
            rm -rf *
            cd /home/jenkins
            mkdir -p .docker
            cp /etc/dockerconfig.json .docker/config.json
            cp -R /etc/.aws ./
            cd $wss
            """
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            tiflash_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                dir ('centos7') {
                    sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${tiflash_sha1}/centos7/tiflash.tar.gz | tar xz"
                }
            }

            stage('Push Centos7 Binary') {
                def target = "tiflash-${RELEASE_TAG}-linux-amd64"
                def ws = pwd()

                dir("${target}") {
                    // 现在将 fileserver 压缩包里的所有内容都放进 release 包。虽然相当于不解压，但是这个 cp 的过程仍应留着，有利于以后修改，且和其他 release 任务保持统一。
                    sh """
                cp -R ${ws}/centos7/tiflash/* ./

		wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf"
                 md5sum "PingCAP Community Software Agreement(Chinese Version).pdf" > /tmp/chinese.check
                 curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(Chinese Version).pdf.md5" >> /tmp/chinese.check
                 md5sum --check /tmp/chinese.check

                 wget "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf"
                 md5sum "PingCAP Community Software Agreement(English Version).pdf" > /tmp/english.check
                 curl "http://fileserver.pingcap.net/download/archive/pdf/PingCAP Community Software Agreement(English Version).pdf.md5" >> /tmp/english.check
                 md5sum --check /tmp/english.check
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
            stage('Push Docker Image') {
                def harbor_image = "hub.pingcap.net/tiflash/tiflash:${RELEASE_TAG}"
                def docker_hub_image = "pingcap/tiflash:${RELEASE_TAG}"
                withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                }
                sh """
                docker pull ${harbor_image}
                docker tag ${harbor_image} ${docker_hub_image}
                docker push ${docker_hub_image}

                """
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration}` Mins" + "\n" +
    "tiflash Branch: `${RELEASE_TAG}`, Githash: `${tiflash_sha1.take(7)}`" + "\n" +
    "tiflash Binary Download URL:" + "\n" +
    "http://download.pingcap.org/tiflash-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
    "tiflash Binary sha256   URL:" + "\n" +
    "http://download.pingcap.org/tiflash-${RELEASE_TAG}-linux-amd64.sha256" + "\n"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    } else {
        slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
