@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    }
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc", "auto-scaling-improvement"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

catchError {
    stage("check release note") {
            //sh "echo $ghprbPullLongDescription | egrep 'Release note'"
            //sh "python -v"
        node("${GO_BUILD_SLAVE}") {
            //def goVersion = new Utils(this).detectGoVersion("https://raw.githubusercontent.com/pingcap/tidb/master/circle.yml")
            //buildSlave = GO_BUILD_SLAVE
            //testSlave = GO_TEST_SLAVE
            //sh "echo $ghprbPullLongDescription"
            println "description $ghprbPullLongDescription"
            sh """
            mkdir -p $ghprbActualCommit
            rm -rf $ghprbActualCommit/description.txt
            cat <<"EOT" >> $ghprbActualCommit/description.txt
$ghprbPullLongDescription
EOT"""
            //echo "$ghprbPullLongDescription" > a.out
            //sh "echo \"$ghprbPullLongDescription\" > $ghprbActualCommit"
            sh "egrep 'Release note.*\\\\r\\\\n[-|\\*].+' $ghprbActualCommit/description.txt || ( echo 'No release note, Please follow https://github.com/pingcap/community/blob/master/contributors/release-note-checker.md' && exit 1) "

            //echo "GO: $goVersion BUILD: $buildSlave TEST: $testSlave"
        }

    }
    currentBuild.result = "SUCCESS"
}
stage("summary") {
    if (currentBuild.result != "SUCCESS") {
        node("master") {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                sh """
                    rm -f comment-pr
                    curl -O http://fileserver.pingcap.net/download/comment-pr
                    chmod +x comment-pr
                    ./comment-pr --token=$TOKEN --owner=pingcap --repo=pd --number=${ghprbPullId} --comment="No release note, Please follow https://github.com/pingcap/community/blob/master/contributors/release-note-checker.md"
                """
            }
        }
    }
}
