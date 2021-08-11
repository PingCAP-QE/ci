def notRun = 1

echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
}

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def testStartTimeMillis = System.currentTimeMillis()

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
        it.startsWith('release-') ? it.minus('release-') : it 
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
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
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
    GO_TEST_HEAVY_SLAVE = "test_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_HEAVY_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
println "GO_TEST_HEAVY_SLAVE=${GO_TEST_HEAVY_SLAVE}"

try {
    timestamps {
        stage("Pre-check"){
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
                throw new RuntimeException("hasBeenTested")
            }
        }

        def buildSlave = "${GO_BUILD_SLAVE}"
        def testSlave = "${GO_TEST_SLAVE}"

        stage('Prepare') {
            def prepareStartTime = System.currentTimeMillis()

            def prepares = [:]

            prepares["Part #1"] = {
                node(buildSlave) {
                    def ws = pwd()
                    deleteDir()

                    container("golang") {

                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap/tidb-test") {
                            timeout(10) {
                                def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                                sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                            """
                                def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                                def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                                sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                            curl ${tidb_test_url} | tar xz

                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd tidb_test && ./build.sh && cd ..
                            if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                cd randgen-test && ./build.sh && cd ..
                                cd randgen-test && ls t > packages.list
                                split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                                cd ..
                            fi
                            """
                            }
                        }
                    }

                    stash includes: "go/src/github.com/pingcap/tidb-test/_helper.sh", name: "helper"
                    stash includes: "go/src/github.com/pingcap/tidb-test/tidb_test/**", name: "tidb_test"
                    stash includes: "go/src/github.com/pingcap/tidb-test/randgen-test/**", name: "randgen-test"
                    stash includes: "go/src/github.com/pingcap/tidb-test/go-sql-test/**", name: "go-sql-test"
                    stash includes: "go/src/github.com/pingcap/tidb-test/go.*,go/src/github.com/pingcap/tidb-test/util/**,go/src/github.com/pingcap/tidb-test/bin/**", name: "tidb-test"
                    deleteDir()
                }
            }

            prepares["Part #2"] = {
                node(buildSlave) {
                    def ws = pwd()
                    deleteDir()


                    container("golang") {

                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap/tidb-test") {
                            container("golang") {
                                timeout(10) {
                                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                                    sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                                """
                                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                                    sh """
                                echo ${tidb_test_url} 
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                                curl ${tidb_test_url} | tar xz

                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                cd mysql_test && ./build.sh && cd ..
                                cd analyze_test && ./build.sh && cd ..
                                """
                                }
                            }
                        }
                    }

                    stash includes: "go/src/github.com/pingcap/tidb-test/_vendor/**", name: "tidb-test-vendor"
                    stash includes: "go/src/github.com/pingcap/tidb-test/mysql_test/**", name: "mysql_test"
                    stash includes: "go/src/github.com/pingcap/tidb-test/analyze_test/**", name: "analyze_test"
                    stash includes: "go/src/github.com/pingcap/tidb-test/gorm_test/**", name: "gorm_test"
                    deleteDir()
                }
            }

            parallel prepares
        }

        stage('Integration Common Test') {
            testStartTimeMillis = System.currentTimeMillis()
            def tests = [:]

            def run = { test_dir, mytest, test_cmd ->
                node("${GO_TEST_HEAVY_SLAVE}") {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        container("golang") {
                            // def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                            // def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                            // tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                            // def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                            // def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                            // pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                            timeout(10) {
                                retry(3){
                                    sh """
	                            tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
	                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"
	
	                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
	                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"
	
	
	                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
	                            curl \${tikv_url} | tar xz bin
	
	                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
	                            curl \${pd_url} | tar xz bin
	
	                            mkdir -p ./tidb-src
	                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
	                            curl ${tidb_url} | tar xz -C ./tidb-src
	                            ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
	
	                            mv tidb-src/bin/tidb-server ./bin/tidb-server
	                            """
                                }

                            }

                            try {
                                timeout(10) {
                                    sh """
                                ps aux
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e
                                
                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10
                                if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi

                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                export log_level=debug
                                TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                TIKV_PATH='127.0.0.1:2379' \
                                TIDB_TEST_STORE_NAME=tikv \
                                ${test_cmd}
                                """
                                }
                            } catch (err) {
                                sh"""
                            cat mysql-test.out || true
                            """
                                sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat tidb*.log
                            """
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
                    //deleteDir()
                }
            }

            def run_split = { test_dir, mytest, test_cmd, chunk ->
                node("${GO_TEST_HEAVY_SLAVE}") {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                        container("golang") {

                            timeout(10) {
                                retry(3){
                                    sh """
	                            tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
	                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"
	
	                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
	                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"
	
	                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 15; done
	                            curl \${tikv_url} | tar xz
	
	                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 15; done
	                            curl \${pd_url} | tar xz
	
	                            mkdir -p ./tidb-src
	                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 15; done
	                            curl ${tidb_url} | tar xz -C ./tidb-src
	                            ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
	
	                            mv tidb-src/bin/tidb-server ./bin/tidb-server
	                            """
                                }
                            }

                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e
                                
                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 10
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                
                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 10
                                if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi
                                if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                    mv t t_bak
                                    mkdir t
                                    cd t_bak
                                    cp \$(cat ../packages_${chunk}) ../t
                                    cd ..
                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                    export log_level=debug
                                    TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                    TIKV_PATH='127.0.0.1:2379' \
                                    TIDB_TEST_STORE_NAME=tikv \
                                    ${test_cmd}
                                fi
                                """
                                }
                            } catch (err) {
                                sh"""
                            cat mysql-test.out || true
                            """

                                sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat tidb*.log
                            """
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
                    deleteDir()
                }
            }

            tests["Integration Randgen Test 1"] = {
                run_split("randgen-test", "randgentest", "./test.sh", 1)
            }

            tests["Integration Randgen Test 2"] = {
                run_split("randgen-test", "randgentest", "./test.sh", 2)
            }

            tests["Integration Randgen Test 3"] = {
                run_split("randgen-test", "randgentest", "./test.sh", 3)
            }

            tests["Integration Analyze Test"] = {
                run("analyze_test", "analyzetest", "./test.sh")
            }

            tests["Integration TiDB Test 1"] = {
                run("tidb_test", "tidbtest", "TEST_FILE=ql_1.t ./test.sh")
            }

            tests["Integration TiDB Test 2"] = {
                run("tidb_test", "tidbtest", "TEST_FILE=ql_2.t ./test.sh")
            }

            tests["Integration Go SQL Test"] = {
                run("go-sql-test", "gosqltest", "./test.sh")
            }

            tests["Integration GORM Test"] = {
                run("gorm_test", "gormtest", "./test.sh")
            }

            tests["Integration MySQL Test"] = {
                run("mysql_test", "mysqltest", "./test.sh")
            }

            tests["Integration MySQL Test Cached"] = {
                run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh")
            }

            tests["Integration Explain Test"] = {
                node ("${GO_TEST_HEAVY_SLAVE}") {
                    def ws = pwd()
                    deleteDir()
                    // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    dir("go/src/github.com/pingcap/tidb") {
                        container("golang") {
                            try {
                                timeout(10) {
                                    retry(3){
                                        deleteDir()
                                        sh """
	                                while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
	                                curl ${tidb_url} | tar xz
	                                """
                                    }

                                }

                                timeout(20) {
                                    sh """
                                if [ ! -d cmd/explaintest ]; then
                                    echo "no explaintest file found in 'cmd/explaintest'"
                                    exit -1
                                fi
                                cp bin/tidb-server cmd/explaintest
                                cp bin/importer cmd/explaintest
                                cd cmd/explaintest
                                GO111MODULE=on go build -o explain_test
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                ./run-tests.sh -s ./tidb-server -i ./importer -b n
                                """
                                }
                            } catch (err) {
                                sh """
                            cat tidb*.log || true
                            """
                                sh "cat explain-test.out || true"
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
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
        node("${GO_BUILD_SLAVE}"){
            container("golang"){
                sh """
		    echo "done" > done
		    curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
            }
        }
    }
}
catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println "catch_exception FlowInterruptedException"
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println "catch_exception AbortException"
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println "catch_exception InterruptedException"
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    println "catch_exception Exception"
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - testStartTimeMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "SUCCESS" && duration >= 3 && ghprbTargetBranch == "master") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master") {
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.25:36000/api/v1/ci/job/sync || true"""
    }
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
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}