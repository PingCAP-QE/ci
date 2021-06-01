def slackcolor = 'good'
def githash

def refSpec = '+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*'
if (ghprbPullId == null || ghprbPullId == "") {
    refSpec = "+refs/heads/*:refs/remotes/origin/*"
}

try {
    node("build_tikv") {
        def ws = pwd()
        // deleteDir()
        container("rust") {
            stage("Prepare") {
                dir("${ws}/go/src/github.com/pingcap/importer") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: refSpec, url: 'git@github.com:tikv/importer.git']]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: refSpec, url: 'git@github.com:tikv/importer.git']]]
                        }
                    }
                    sh "git checkout -f ${ghprbActualCommit}"
                }
            }

            stage("Test") {
                dir("${ws}/go/src/github.com/pingcap/importer") {
                    timeout(30) {
                        sh """
                        uname -a
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        make build 
                        """
                    }
                    deleteDir()
                }

            }
        }
    }
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Unit Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}