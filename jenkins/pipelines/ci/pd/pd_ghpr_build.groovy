def slackcolor = 'good'
def githash

try {
    node("${GO_BUILD_SLAVE}") {
        def ws = pwd()
        deleteDir()

        stage("debug info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 
            println "work space path:\n${ws}"
        }

        stage("Checkout") {
            // update cache
            dir("/home/jenkins/agent/git/pd") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/pd.git']]]
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(20) {
                        sh """
                        cp -R /home/jenkins/agent/git/pd/. ./
                        git checkout -f ${ghprbActualCommit}
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go WITH_RACE=1 make && mv bin/pd-server bin/pd-server-race
                        GOPATH=${ws}/go make
                        """
                    }
                }
            }
        }

        stage("Upload") {
            def filepath = "builds/pingcap/pd/pr/${ghprbActualCommit}/centos7/pd-server.tar.gz"
            def refspath = "refs/pingcap/pd/pr/${ghprbPullId}/sha1"

            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(10) {
                        sh """
                        rm -rf .git
                        tar czvf pd-server.tar.gz ./*
                        curl -F ${filepath}=@pd-server.tar.gz ${FILE_SERVER_URL}/upload
                        echo "pr/${ghprbActualCommit}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """                        
                    }
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

stage("upload status"){
    node{
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "build pd: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}