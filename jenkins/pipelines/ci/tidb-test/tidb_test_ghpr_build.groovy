def slackcolor = 'good'
def githash

def TIDB_BRANCH = ({
    def m = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m) {
        return "${m.group(1)}"
    }
    return "master"
}).call()

def downRef = { name ->
    def m = name.trim() =~ /^pr\/(\d+)$/
    if (m) {
        return "pull/${m[0][1]}/head"
    }
    return name
}
def downUrl = "https://api.github.com/repos/pingcap/tidb/tarball/${downRef(TIDB_BRANCH)}"

println "TIDB_BRANCH=${TIDB_BRANCH} DOWNLOAD_URL=${downUrl}"
def build_node = "build_go1130"
try {
    if(ghprbTargetBranch in ["release-2.0"]) {
        build_node = "build_go1130"
    }
    node(build_node) {
        def ws = pwd()

        stage("Checkout") {
            // update cache
            parallel 'tidb-test': {
                dir("go/src/github.com/pingcap/tidb-test") {
                    checkout(changelog: false, poll: false, scm: [
                        $class: "GitSCM",
                        branches: [
                            [name: "${ghprbActualCommit}"],
                        ],
                        userRemoteConfigs: [
                            [
                                url: "git@github.com:pingcap/tidb-test.git",
                                refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                credentialsId: 'github-sre-bot-ssh',
                            ]
                        ],
                        extensions: [
                            [$class: 'PruneStaleBranch'],
                            [$class: 'CleanBeforeCheckout'],
                        ],
                    ])
                }
            }, 'tidb': {
                dir("go/src/github.com/pingcap/tidb") {
                    deleteDir()
                    sh("wget -O- ${downUrl} | tar xz --strip=1")
                }
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    timeout(10) {
                        sh """
                        TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb make check
                        """
                    }
                    for (binCase in ['partition_test', 'coprocessor_test', 'concurrent-sql']) {
                        if (fileExists("${binCase}/build.sh")) { dir(binCase) { sh "bash build.sh" } }
                    }
                }
            }
        }

        stage("Upload") {
            def filepath = "builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
            def refspath = "refs/pingcap/tidb-test/pr/${ghprbPullId}/sha1"

            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    timeout(10) {
                        sh """
                        rm -rf .git
                        tar czvf tidb-test.tar.gz ./*
                        curl -F ${filepath}=@tidb-test.tar.gz ${FILE_SERVER_URL}/upload
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

stage('Summary') {
    echo "Send slack here ..."
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
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}
