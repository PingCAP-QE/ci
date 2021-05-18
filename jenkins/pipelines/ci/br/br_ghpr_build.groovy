catchError {
    node("build_go1130") {
        def ws = pwd()
        deleteDir()
        container("golang") {
            deleteDir()
            dir("/home/jenkins/agent/git/br") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/br.git']]]
                } catch (error) {
                    retry(2) {
                        echo "checkout failed, retry.."
                        sleep 60
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/br.git']]]
                    }
                }
            }

            dir("go/src/github.com/pingcap/br") {
                deleteDir()
                stage('Build') {
                    sh """
                        cp -R /home/jenkins/agent/git/br/. ./
                        git checkout -f ${ghprbActualCommit}
                        GOPATH=\$GOPATH:${ws}/go make 
                    """
                }

                stage("Upload") {
                    def filepath = "builds/pingcap/br/pr/${ghprbActualCommit}/centos7/br.tar.gz"
                    def donepath = "builds/pingcap/br/pr/${ghprbActualCommit}/centos7/done"
                    def refspath = "refs/pingcap/br/pr/${ghprbPullId}/sha1"

                    timeout(10) {
                        sh """
                        rm -rf .git
                        tar --exclude=br.tar.gz -czvf br.tar.gz ./bin
                        curl -F ${filepath}=@br.tar.gz ${FILE_SERVER_URL}/upload
                        echo "pr/${ghprbActualCommit}" > sha1
                        echo "done" > done
                        sleep 20
                        curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Build Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
