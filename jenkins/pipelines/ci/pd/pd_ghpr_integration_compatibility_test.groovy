echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__pd_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = "add-log"
def PD_OLD_BRANCH = "${ghprbTargetBranch}"

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse tidb branch
def m2 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    TIDB_BRANCH = "${m2[0][1]}"
}
m2 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null

if (TIDB_TEST_BRANCH == "release-3.0" ||TIDB_TEST_BRANCH == "release-3.1" ) {
   TIDB_TEST_BRANCH = "release-3.0"
}

println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// parse pd branch
def m4 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    PD_OLD_BRANCH = "${m4[0][1]}"
}
m4 = null
println "PD_OLD_BRANCH=${PD_OLD_BRANCH}"

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

def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/pr/${ghprbActualCommit}/centos7/pd-server.tar.gz"

try {
    stage('Prepare') {
        node("${GO_BUILD_SLAVE}") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            def ws = pwd()
            deleteDir()

            container("golang") {
                def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                dir("go/src/github.com/pingcap/tidb") {
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }

                dir("go/src/github.com/PingCAP-QE/tidb-test") {
                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                    timeout(10) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                        curl ${tidb_test_url} | tar xz
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sT \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        cd compatible_test && GOPATH=${ws}/go ./build.sh
                        """
                    }
                }
            }

            stash includes: "go/src/github.com/PingCAP-QE/tidb-test/compatible_test/**", name: "compatible_test"
        }
    }

    stage('Integration Compatibility Test') {
        node("${GO_TEST_SLAVE}") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            def ws = pwd()
            deleteDir()
            unstash 'compatible_test'

            dir("go/src/github.com/PingCAP-QE/tidb-test/compatible_test") {
                container("golang") {
                    def pd_old_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_OLD_BRANCH}/sha1"
                    def pd_old_sha1 = sh(returnStdout: true, script: "curl ${pd_old_refs}").trim()
                    def pd_old_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_old_sha1}/centos7/pd-server.tar.gz"

                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                    def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                   // def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"


                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        curl ${tikv_url} | tar xz

                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz

                        mkdir -p ./pd-old-src
                        cd ./pd-old-src
                        echo ${pd_old_url}
                        echo ${pd_old_refs}
                        while ! curl --output /dev/null --silent --head --fail ${pd_old_url}; do sleep 15; done
                        curl ${pd_old_url} | tar xz  || true
                        cd ..

                        mkdir -p ./pd-src
                        cd ./pd-src
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz   || true
                        cd ..

                        mv pd-old-src/bin/pd-server bin/pd-server-old
                        mv pd-src/bin/pd-server ./bin/pd-server
                        """
                    }

                    timeout(10) {
                        try {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            export log_level=debug
                            TIKV_PATH=./bin/tikv-server \
                            TIDB_PATH=./bin/tidb-server \
                            PD_PATH=./bin/pd-server \
                            UPGRADE_PART=pd \
                            NEW_BINARY=./bin/pd-server \
                            OLD_BINARY=./bin/pd-server-old \
                            GOPATH=${ws}/go ./test.sh 2>&1
                            """
                        } catch (err) {
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                            sh "cat tidb.log"
                            sh "cat tikv.log"
                            sh "cat pd.log"
                        }
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}
catch(Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration Compatibility Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (params.containsKey("triggered_by_upstream_ci")) {
        stage("update commit status") {
            node("master") {
                if (currentBuild.result == "ABORTED") {
                    PARAM_DESCRIPTION = 'Jenkins job aborted'
                    // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                    PARAM_STATUS = 'error'
                } else if (currentBuild.result == "FAILURE") {
                    PARAM_DESCRIPTION = 'Jenkins job failed'
                    PARAM_STATUS = 'failure'
                } else if (currentBuild.result == "SUCCESS") {
                    PARAM_DESCRIPTION = 'Jenkins job success'
                    PARAM_STATUS = 'success'
                } else {
                    PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                    PARAM_STATUS = 'error'
                }
                def default_params = [
                        string(name: 'PD_COMMIT_ID', value: ghprbActualCommit ),
                        string(name: 'CONTEXT', value: 'idc-jenkins-ci-pd/integration-compatibility-test'),
                        string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                        string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                        string(name: 'STATUS', value: PARAM_STATUS ),
                ]
                echo("default params: ${default_params}")
                build(job: "pd_update_commit_status", parameters: default_params, wait: true)
            }
        }
    }
}
