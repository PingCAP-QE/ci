def PD_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch
def TIKV_OLD_BRANCH = "${ghprbTargetBranch}"

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

// parse tikv branch
def m1 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    PD_BRANCH = "${m1[0][1]}"
}
m1 = null
println "PD_BRANCH=${PD_BRANCH}"

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
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// parse pd branch
def m4 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIKV_OLD_BRANCH = "${m4[0][1]}"
}
m4 = null
println "TIKV_OLD_BRANCH=${TIKV_OLD_BRANCH}"

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], ghprbTargetBranch)
if (isNeedGo1160) {
    println "This build use go1.16 because ghprTargetBranch=master"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = "test_heavy_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Prepare') {
        node("${GO_TEST_SLAVE}") {
            def ws = pwd()
            deleteDir()

            container("golang") {
                def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                dir("go/src/github.com/pingcap/tidb") {
                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz
                        """
                    }
                }

                dir("go/src/github.com/pingcap/tidb-test") {
                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

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

            stash includes: "go/src/github.com/pingcap/tidb-test/compatible_test/**", name: "compatible_test"
            deleteDir()
        }
    }

    stage('Integration Compatibility Test') {
        node("${GO_TEST_SLAVE}") {
            def ws = pwd()
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            deleteDir()
            unstash 'compatible_test'

            dir("go/src/github.com/pingcap/tidb-test/compatible_test") {
                container("golang") {
                    def tikv_old_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_OLD_BRANCH}/sha1"
                    def tikv_old_sha1 = sh(returnStdout: true, script: "curl ${tikv_old_refs}").trim()
                    def tikv_old_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_old_sha1}/centos7/tikv-server.tar.gz"

                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                    def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"


                    timeout(30) {
                        sh """
                        while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                        curl ${pd_url} | tar xz ./bin bin || true

                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                        curl ${tidb_url} | tar xz

                        mkdir -p ./tikv-old-src
                        cd ./tikv-old-src
                        echo ${tikv_old_url}
                        echo ${tikv_old_refs}
                        while ! curl --output /dev/null --silent --head --fail ${tikv_old_url}; do sleep 15; done
                        curl ${tikv_old_url} | tar xz
                        cd ..

                        mkdir -p ./tikv-src
                        cd ./tikv-src
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        sleep 15
                        (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        cd ..

                        mv tikv-old-src/bin/tikv-server bin/tikv-server-old
                        mv tikv-src/bin/tikv-server ./bin/tikv-server
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
                            UPGRADE_PART=tikv \
                            NEW_BINARY=./bin/tikv-server \
                            OLD_BINARY=./bin/tikv-server-old \
                            GOPATH=${ws}/go ./test.sh 2>&1
                            """
                        } catch (err) {
                            sh "cat *.log"
                            throw err
                        } finally {
                            sh """
                            set +e 
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
            deleteDir()
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
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}