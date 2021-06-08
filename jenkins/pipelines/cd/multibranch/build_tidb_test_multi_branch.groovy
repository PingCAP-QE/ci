def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], env.BRANCH_NAME)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"


def BUILD_URL = 'git@github.com:pingcap/tidb-test.git'
def slackcolor = 'good'
def githash
def master_branch_node = "${GO_BUILD_SLAVE}"
def branchNodeMap = [
    "master" : master_branch_node,
    "release-2.0" : "build_go1120",
    "release-2.1" : "build_go1120"
]
try {
    // 如果不在 map 里，如 release-3.0 分支或者 tag 分支，就使用和 master 一样的环境
    node(branchNodeMap.get("${env.BRANCH_NAME}".toString(), master_branch_node)) {
        def ws = pwd()
        //deleteDir()
        stage("Debug Info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }
        stage("Checkout") {
            dir("go/src/github.com/pingcap/tidb-test") {
                // 如果不是 TAG，直接传 branch 给下面的 checkout 语句； 否则就应该 checkout 到 refs/tags 下 .
                // 值得注意的是，即使传入的是 TAG，环境变量里的 BRANCH_NAME 和 TAG_NAME 同时会是 TAG 名，如 v3.0.0
                def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"

                git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "${branch}"
                
                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    for (binCase in ['partition_test', 'coprocessor_test', 'concurrent-sql']) {
                        if (fileExists("${binCase}/build.sh")) { dir(binCase) { sh "bash build.sh" } }
                    }
                }
            }
        }

        stage("Upload") {
            dir("go/src/github.com/pingcap/tidb-test") {
                def refspath = "refs/pingcap/tidb-test/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/tidb-test/${githash}/centos7/tidb-test.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        tar --exclude=tidb-test.tar.gz -czvf tidb-test.tar.gz ./*
                        curl -F ${filepath}=@tidb-test.tar.gz ${FILE_SERVER_URL}/upload
                        echo "${githash}" > sha1
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

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}