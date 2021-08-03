echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def slackcolor = 'good'
def githash

// job param: notcomment default to True
// /release : not comment binary download url
// /release comment=true : comment binary download url
def m1 = ghprbCommentBody =~ /\/run-build-arm64[\s]comment\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    needComment = "${m1[0][1]}"
    if ( needComment == "true" || needComment == "True" ) {
        notcomment = false
    }
}
m1 = null

binary = "builds/pingcap/test/tikv/${ghprbActualCommit}/centos7/tikv-linux-arm64.tar.gz"
binary_existed = -1


def release_one_arm64(repo,hash) {
    echo "release binary: ${FILE_SERVER_URL}/download/${binary}"
    def paramsBuild = [
            string(name: "ARCH", value: "arm64"),
            string(name: "OS", value: "linux"),
            string(name: "EDITION", value: "community"),
            string(name: "OUTPUT_BINARY", value: binary),
            string(name: "REPO", value: repo),
            string(name: "PRODUCT", value: repo),
            string(name: "GIT_HASH", value: hash),
            string(name: "TARGET_BRANCH", value: ghprbTargetBranch),
            [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild
}


try{

    stage("Check binary") {
        binary_existed = sh(returnStatus: true,
                script: """
		    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/${binary}; then exit 0; else exit 1; fi
		    """)
        if (binary_existed == 0) {
            println "tikv: ${ghprbActualCommit} has beeb build before"
            println "skip this build"
        } else {
            println "this commit need build"
        }

    }

    stage("Build") {
        if (binary_existed == 0) {
            println "skip build..."
        } else {
            release_one_arm64("tikv", ghprbActualCommit)
        }
    }

    stage("Print binary url") {
        println binary
    }

    stage("Comment on pr") {
        // job param: notcomment default to True
        // /release : not comment binary download url
        // /release comment=true : comment binary download url

        if (!notcomment.toBoolean()) {
            node("master") {
                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                    sh """
                    rm -f comment-pr
                    curl -O http://fileserver.pingcap.net/download/comment-pr
                    chmod +x comment-pr
                    ./comment-pr --token=$TOKEN --owner=tikv --repo=tikv --number=${ghprbPullId} --comment="download tikv binary at ${FILE_SERVER_URL}/download/${binary}"
                """
                }
            }
        }
    }
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
