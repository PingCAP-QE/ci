def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}


try {
    node("arm") {
        def ws = pwd()
        deleteDir()

        stage("Checkout") {
            // update cache
            dir("tikv") {
                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
            }
        }

        stage("make clippy") {
            dir("tikv") {
                timeout(120) {
                    sh """
                        git checkout -f ${ghprbActualCommit}
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        make clippy
                    """
                }
            }
        }

        stage("make clippy with optional features") {
            dir("tikv") {
                timeout(120) {
                    sh """
                        git checkout -f ${ghprbActualCommit}
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        ENABLE_FEATURES="test-engines-panic nortcheck" NO_DEFAULT_TEST_ENGINES=1 make clippy
                    """
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
