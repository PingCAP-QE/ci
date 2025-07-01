def notRun = 1
if (!params.force){
    node("${GO_BUILD_SLAVE}"){
        container("golang"){
            notRun = sh(returnStatus: true, script: """
			if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
			""")
        }
    }
}

if (notRun == 0){
    println "the ${ghprbActualCommit} has been tested"
    return
}
def slackcolor = 'good'
def githash
def failpointPath = "github.com/pingcap/gofail"
def failpoint = "gofail"
def goimportsPath = "golang.org/x/tools/cmd/goimports"
def goimports = "goimports"

if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__pd_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/pr/${ghprbActualCommit}/centos7/pd-server.tar.gz"

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

try {
    stage('Prepare') {failpointPath
        node ("${GO_TEST_SLAVE}") {
            def ws = pwd()
            deleteDir()
            dir("go/src/github.com/pingcap/pd") {
                container("golang") {
                    timeout(30) {
                        sh """
                        pwd
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        sleep 5
                        curl ${pd_url} | tar xz
                        rm -rf ./bin
                        export GOPATH=${ws}/go
                        go list ./...
                        go list ./... | grep -v -E  "github.com/tikv/pd/server/api|github.com/tikv/pd/tests/client|github.com/tikv/pd/tests/server/tso|github.com/tikv/pd/server/schedule" > packages.list
                        cat packages.list
                        split packages.list -n r/6 packages_unit_ -a 1 --numeric-suffixes=1
                        cat packages_unit_1
                        echo "github.com/tikv/pd/server/api" >> packages_unit_8
                        echo "github.com/tikv/pd/tests/client" >> packages_unit_9
                        echo "github.com/tikv/pd/tests/server/tso" >> packages_unit_9
                        echo "github.com/tikv/pd/server/schedule" >> packages_unit_7
                        """

                        if (ghprbTargetBranch == "release-3.0" || ghprbTargetBranch == "release-3.1") {
                            sh """
                            make retool-setup
                            make failpoint-enable
                            """
                        } else {
                            sh """
                            make failpoint-enable
                            make deadlock-enable
    						"""
                        }
                    }
                }
            }

            stash includes: "go/src/github.com/pingcap/pd/**", name: "pd"
        }
    }

    stage('Unit Test') {
        def run_unit_test = { chunk_suffix ->
            node("${GO_TEST_SLAVE}") {
                def ws = pwd()
                deleteDir()
                unstash 'pd'

                dir("go/src/github.com/pingcap/pd") {
                    container("golang") {
                        timeout(30) {
                            sh """
                               set +e
                               killall -9 -r tidb-server
                               killall -9 -r tikv-server
                               killall -9 -r pd-server
                               rm -rf /tmp/pd
                               set -e
                               cat packages_unit_${chunk_suffix}
                            """
                            if (fileExists("go.mod")) {
                                sh """
                               mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sfT \$GOPATH/pkg/mod ${ws}/go/pkg/mod || true
                               GOPATH=${ws}/go CGO_ENABLED=1 GO111MODULE=on go test -p 1 -race -cover \$(cat packages_unit_${chunk_suffix})
                               """
                            } else {
                                sh """
                               GOPATH=${ws}/go CGO_ENABLED=1 GO111MODULE=off go test -race -cover \$(cat packages_unit_${chunk_suffix})
                               """
                            }
                        }
                    }
                }
            }
        }
        def tests = [:]

        tests["Unit Test Chunk #1"] = {
            run_unit_test(1)
        }

        tests["Unit Test Chunk #2"] = {
            run_unit_test(2)
        }

        tests["Unit Test Chunk #3"] = {
            run_unit_test(3)
        }

        tests["Unit Test Chunk #4"] = {
            run_unit_test(4)
        }

        tests["Unit Test Chunk #5"] = {
            run_unit_test(5)
        }

        tests["Unit Test Chunk #6"] = {
            run_unit_test(6)
        }

        tests["Unit Test Chunk #7"] = {
            run_unit_test(7)
        }

        tests["Integrate Test Chunk #1"] = {
            run_unit_test(8)
        }

        tests["Integrate Test Chunk #2"] = {
            run_unit_test(9)
        }
        parallel tests
    }

    currentBuild.result = "SUCCESS"
    node("${GO_TEST_SLAVE}"){
        container("golang"){
            sh """
		    echo "done" > done
		    curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
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
