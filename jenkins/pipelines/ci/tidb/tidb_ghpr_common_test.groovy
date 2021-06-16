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

def TIDB_TEST_BRANCH = ghprbTargetBranch

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
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

def buildSlave = "${GO_BUILD_SLAVE}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def TIDB_TEST_STASH_FILE = "tidb_test_${UUID.randomUUID().toString()}.tar"

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
        //def buildSlave = "${GO_BUILD_SLAVE}"
        def testSlave = "${GO_TEST_SLAVE}"

        stage('Prepare') {

            node(buildSlave) {
                def ws = pwd()
                deleteDir()

                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                println "work space path:\n${ws}"

                container("golang") {

                    // sh "go version"

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
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 5; done
                        """
                            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                        curl ${tidb_test_url} | tar xz
                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                        # export GOPROXY=http://goproxy.pingcap.net
                        cd tidb_test && ./build.sh && cd ..
                        cd mysql_test && ./build.sh && cd ..
                        cd randgen-test && ./build.sh && cd ..
                        cd analyze_test && ./build.sh && cd ..
                        if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                            cd randgen-test && ls t > packages.list
                            split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                            cd ..
                        fi
                        """

                            sh """
                            echo "stash tidb-test"
                            cd .. && tar -cf $TIDB_TEST_STASH_FILE tidb-test/
                            curl -F builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE}=@${TIDB_TEST_STASH_FILE} ${FILE_SERVER_URL}/upload
                        """
                        }
                    }
                }

                // stash includes: "go/src/github.com/pingcap/tidb-test/**", name: "tidb-test"

                deleteDir()
            }
        }

        stage('Common Test') {
            def tests = [:]

            def run_with_log = { test_dir, log_path ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    // unstash 'tidb-test'

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"

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
                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat ${log_path}"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                }
            }

            def run = { test_dir ->
                if (test_dir == "mysql_test"){
                    run_with_log("mysql_test", "mysql-test.out*")
                } else{
                    run_with_log(test_dir, "tidb*.log")
                }
            }

            def run_split = { test_dir, chunk ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    // unstash 'tidb-test'

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"

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

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh
                                if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                    mv t t_bak
                                    mkdir t
                                    cd t_bak
                                    cp \$(cat ../packages_${chunk}) ../t
                                    cd ..
                                fi
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log*"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    //deleteDir()
                }
            }

            def run_cache_log = { test_dir, log_path ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    // unstash 'tidb-test'

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"

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

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                CACHE_ENABLED=1 ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat ${log_path}"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    deleteDir()
                }
            }

            def run_cache = { test_dir ->
                run_cache_log(test_dir, "tidb*.log*")
            }

            def run_vendor = { test_dir ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    // unstash 'tidb-test'

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                                sh """
                            if [ -f go.mod ]; then
                                GO111MODULE=on go mod vendor -v
                            fi
                            """
                            }
                        }

                        dir("go/src/github.com/pingcap/tidb_gopath") {
                            sh """
                        mkdir -p ./src
                        cp -rf ../tidb/vendor/* ./src
                        mv ../tidb/vendor ../tidb/_vendor
                        """
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    deleteDir()
                }
            }

            def run_jdbc = { test_dir, testsh ->
                node("test_java") {
                    def ws = pwd()
                    deleteDir()
                    // unstash 'tidb-test'

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"

                    container("java") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                mkdir -p ~/.m2 && cat <<EOF > ~/.m2/settings.xml
<settings>
  <mirrors>
    <mirror>
      <id>alimvn-central</id>
      <name>aliyun maven mirror</name>
      <url>https://maven.aliyun.com/repository/central</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF
                                
                                cat ~/.m2/settings.xml || true

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=disable GOROOT=disable ${testsh}
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log"
                                sh "cat *tidb.log"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    //deleteDir()
                }
            }

            tests["TiDB Test"] = {
                run("tidb_test")
            }


            tests["Randgen Test 1"] = {
                run_split("randgen-test",1)
            }

            tests["Randgen Test 2"] = {
                run_split("randgen-test",2)
            }

            tests["Randgen Test 3"] = {
                run_split("randgen-test",3)
            }

            tests["Analyze Test"] = {
                run("analyze_test")
            }

            tests["Mysql Test"] = {
                run("mysql_test", "mysql-test.out*")
            }

            if ( ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-3") ) {
                tests["Mysql Test Cache"] = {
                    run_cache_log("mysql_test", "mysql-test.out*")
                }
            }

            tests["JDBC Fast"] = {
                run_jdbc("jdbc_test", "./test_fast.sh")
            }

            tests["JDBC Slow"] = {
                run_jdbc("jdbc_test", "./test_slow.sh")
            }

            tests["Gorm Test"] = {
                run("gorm_test")
            }

            tests["Go SQL Test"] = {
                run("go-sql-test")
            }

            tests["DDL ETCD Test"] = {
                run_vendor("ddl_etcd_test")
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
        node(buildSlave){
            container("golang"){
                sh """
		    echo "done" > done
		    curl -F ci_check/tidb_ghpr_common_test/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
            }
        }
    }
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}

stage('Summary') {
    echo "success"
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Common Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "SUCCESS" && duration >= 3 && ghprbTargetBranch == "master") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}