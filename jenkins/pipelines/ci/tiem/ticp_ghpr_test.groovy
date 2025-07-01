def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = true
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
repo_url = "git@github.com:pingcap-inc/tiem.git"

node("${GO_TEST_SLAVE}") {
    def ws = pwd()
    deleteDir()

    dir("${ws}/go/src/github.com/pingcap-inc/tiem") {
        stage("Prepare"){
            container("golang") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                def specStr = "+refs/heads/*:refs/remotes/origin/*"
                if (ghprbPullId != null && ghprbPullId != "") {
                    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: repo_url]]]
                } catch (error) {
                    retry(2) {
                        echo "checkout failed, retry.."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: repo_url]]]
                    }
                }

                sh "git checkout -f ${ghprbActualCommit}"
            }
        }
    }

    stage("Test") {
        container("golang") {
            withCredentials([string(credentialsId: 'TIEM_CODECOV_TOKEN', variable: 'CODECOV_TOKEN')]) {
                dir("go/src/github.com/pingcap-inc/tiem") {
                    try {
                        sh """
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
                        """
                    } catch (err) {
                        echo err.getMessage()
                        currentBuild.result = "FAILURE"
                    } finally {
                        sh """
                            CODECOV_TOKEN=${CODECOV_TOKEN} JenkinsCI=1 GOPATH=\$GOPATH:${ws}/go make coverage
                        """
                    }
                }
            }
        }
    }
}
