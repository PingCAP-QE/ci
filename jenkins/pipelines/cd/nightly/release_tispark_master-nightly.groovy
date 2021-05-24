#!groovy
def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"

def tispark_sha1

catchError {
    stage('Push Centos7 Binary') {
        node('delivery') {
            container('delivery') {
                deleteDir()
                 tispark_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tispark/${BUILD_BRANCH}/sha1").trim()
                 sh "curl -O ${FILE_SERVER_URL}/download/builds/pingcap/tispark/${tispark_sha1}/centos7/tispark.tar.gz"
                 sh "pwd"
                 sh "ls ./"
                 
                def target = "tispark-assembly-${RELEASE_TAG}-linux-amd64"
                sh "pwd"
                sh "ls ./"
                sh "mv tispark.tar.gz ${target}.tar.gz"

                sh """
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
        }
    }
    currentBuild.result = "SUCCESS"
}

stage('Summary') {
   def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
   def slackmsg = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
   "Elapsed Time: `${duration}` Mins" + "\n" +
   "tispark Branch: `${BUILD_BRANCH}`, Githash: `${tispark_sha1.take(7)}`" + "\n" +
   "tispark Binary Download URL:" + "\n" +
   "http://download.pingcap.org/tispark-assembly-${RELEASE_TAG}-linux-amd64.tar.gz" + "\n" +
   "tispark Binary sha256   URL:" + "\n" +
   "http://download.pingcap.org/tispark-assembly-${RELEASE_TAG}-linux-amd64.sha256" + "\n"
   
   print slackmsg

   if (currentBuild.result != "SUCCESS") {
       slackSend channel: '#binary_publish', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
   } else {
       slackSend channel: '#binary_publish', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
   }
}
