def notRun = 1
def buildSlave = "${GO_BUILD_SLAVE}"

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

def slackcolor = 'good'
def githash

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"

def runExplainTest = true
def goTestEnv = "CGO_ENABLED=1"
def waitBuildDone = 0
//def goTestEnv = ""
def gofail = ({
    def m = ghprbCommentBody =~ /gofail\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m) {
        return "${m[0][1]}"
    }
    return "pingcap"
})()

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}
if (params.containsKey("release_test")) {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
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

println "$GO1160_BUILD_SLAVE"
println "$GO1160_TEST_SLAVE"

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
        //   def buildSlave = "${GO_BUILD_SLAVE}"
        def testSlave =  "${GO_TEST_SLAVE}"

        stage('Prepare') {
            def builds = [:]

            builds["unittest"] = {
                node (buildSlave) {
                    def ws = pwd()
                    deleteDir()
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    // update code
                    dir("/home/jenkins/agent/code-archive") {
                        // delete to clean workspace in case of agent pod reused lead to conflict.
                        deleteDir()
                        // copy code from nfs cache
                        container("golang") {
                            if(fileExists("/nfs/cache/git-test/src-tidb.tar.gz")){
                                timeout(5) {
                                    sh """
                                        cp -R /nfs/cache/git-test/src-tidb.tar.gz*  ./
                                        mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                        tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                                    """
                                }
                            }
                        }
                        dir("${ws}/go/src/github.com/pingcap/tidb") {
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tidb"
                                echo "Clean dir then get tidb src code from fileserver"
                                deleteDir()
                            }
                            if(!fileExists("${ws}/go/src/github.com/pingcap/tidb/Makefile")) {
                                dir("${ws}/go/src/github.com/pingcap/tidb") {
                                    sh """
                                        rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                                        rm -rf /home/jenkins/agent/code-archive/tidb
                                        wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/source/tidb.tar.gz -q --show-progress
                                        tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                                """
                                }
                            }
                            try {
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                            }   catch (info) {
                                    retry(2) {
                                        echo "checkout failed, retry.."
                                        sleep 5
                                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                            deleteDir()
                                        }
                                        // if checkout one pr failed, we fallback to fetch all thre pr data
                                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                                    }
                                }
                            sh "git checkout -f ${ghprbActualCommit}"
                        }
                    }


                    // update tidb cache
                    dir("go/src/github.com/pingcap/tidb") {
                        container("golang") {
                            timeout(5) {
                                sh """
                            rm -rf ./bin
                            go list ./... | grep -v cmd/ddltest > packages.list
                            package_base=`grep module go.mod | head -n 1 | awk '{print \$2}'`
                            cat packages.list | grep -v "\${package_base}/ddl"|grep -v "\${package_base}/executor" |grep -v "\${package_base}/session" | grep -v "\${package_base}/expression" | grep -vE "\${package_base}/store\$" > packages.list.short
                            echo "\${package_base}/ddl" > packages_race_7
                            cat packages.list | grep "\${package_base}/ddl/" > packages_race_13
                            cat packages.list | grep -E "\${package_base}/store\$" >> packages_race_13

                            echo  "\${package_base}/session"  > packages_race_8
                            grep "\${package_base}/sessionctx" packages.list >>  packages.list.short

                            echo "\${package_base}/executor" > packages_race_9
                            cat packages.list | grep "\${package_base}/executor/" > packages_race_10 || cat packages_race_9 > packages_race_10
                            cat packages.list | grep -v "\${package_base}/executor" > packages.list.short2

                            grep  "\${package_base}/expression/" packages.list >> packages.list.short
                            echo  "\${package_base}/expression" > packages_race_12
                            grep "\${package_base}/planner/core" packages.list.short > packages_race_6
                            grep "\${package_base}/store/tikv" packages.list.short > packages_race_5
                            grep "\${package_base}/server" packages.list.short > packages_race_4

                            cat packages.list.short | grep -v "\${package_base}/planner/core" | grep -v "\${package_base}/store/tikv" | grep -v "\${package_base}/server" > packages.list.short.1
                            mv packages.list.short.1 packages.list.short

                            split packages.list.short -n r/3 packages_race_ -a 1 --numeric-suffixes=1

                            # failpoint-ctl => 3.0+
                            # gofail => 2.0, 2.1
                            set +e
                            grep "tools/bin/failpoint-ctl" Makefile
                            if [ \$? -lt 1 ]; then
                                failpoint_bin=tools/bin/failpoint-ctl
                            else
                                failpoint_bin=tools/bin/gofail
                            fi
                            set -e
                            echo "failpoint bin: \$failpoint_bin"
                            make \$failpoint_bin
                            find . -type d | grep -vE "(\\.git|_tools)" | xargs \$failpoint_bin enable
                            """
                            }
                        }
                    }

                    stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
                    if (fileExists("go/src/github.com/pingcap/tidb/go.mod")) {
                        goTestEnv = goTestEnv + " GO111MODULE=on"
                    }
                }
            }

            parallel builds
        }

        stage('Unit Test') {
            def run_race_test = { chunk_suffix ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    unstash 'tidb'

                    dir("go/src/github.com/pingcap/tidb") {
                        container("golang") {
                            try{
                                timeout(20) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                export log_level=info
                                time ${goTestEnv} go test -v -vet=off -p 5 -timeout 20m -race \$(cat packages_race_${chunk_suffix}) #> test.log
                                """
                                }
                            }catch (err) {
                                throw err
                            }//finally {
                            // sh"""
                            // cat test.log
                            // go get github.com/tebeka/go2xunit
                            // cat test.log | go2xunit > junit.xml
                            // """
                            // junit "junit.xml"
                            //}
                        }
                    }
                }
            }


            def run_race_test_heavy_with_args = { chunk_suffix, extraArgs ->
                node(testSlave) {
                    def ws = pwd()
                    deleteDir()
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    unstash 'tidb'

                    dir("go/src/github.com/pingcap/tidb") {
                        container("golang") {
                            try {
                                timeout(20) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                export log_level=info
                                time GORACE="history_size=7" ${goTestEnv} go test -v -vet=off -p 5 -timeout 20m -race \$(cat packages_race_${chunk_suffix}) ${extraArgs} # > test.log
                                """
                                }
                            }catch (err) {
                                throw err
                            }finally {
                                // sh"""
                                // cat test.log
                                // go get github.com/tebeka/go2xunit
                                // cat test.log | go2xunit > junit.xml
                                // """
                                // junit "junit.xml"
                            }
                        }
                    }
                }
            }

            def run_race_test_heavy = { chunk_suffix, extraArgs ->
                run_race_test_heavy_with_args(chunk_suffix, "")
            }

            def run_race_test_heavy_parallel = { chunk_suffix ->
                run_race_test_heavy_with_args(chunk_suffix, "-check.p")
            }


            // 将执行较慢的 chunk 放在前面优先调度，以减轻调度的延迟对执行时间的影响
            def tests = [:]

            def suites = """testSuite1|testSuite|testSuite7|testSuite5|testSuiteAgg|
                            testSuiteJoin1|tiflashTestSuite|testFastAnalyze|testSuiteP2|testSuite2"""
            def cmd1 = String.format('-check.f "%s"', suites)
            def cmd2 = String.format('-check.exclude "%s"', suites)
            tests["Race Test Chunk #9 executor-part1"] = {
                run_race_test_heavy_with_args(9, cmd1)
            }
            tests["Race Test Chunk #9 executor-part2"] = {
                run_race_test_heavy_with_args(9, cmd2)
            }

            tests["Race Test Chunk #10"] = {
                run_race_test(10)
            }
            // run race #8 in parallel mode for master branch\
            if (ghprbTargetBranch == "master") {
                tests["Race Test Chunk #7 ddl-DBSuite|SerialDBSuite"] = {
                    run_race_test_heavy_with_args(7, '-check.f "testDBSuite|testSerialDBSuite"')
                }

                tests["Race Test Chunk #7 ddl-other suite"] = {
                    run_race_test_heavy_with_args(7, '-check.exclude "testDBSuite|testSerialDBSuite"')
                }

                tests["Race Test Chunk #6 planner/core-testIntegrationSerialSuite"] = {
                    run_race_test_heavy_with_args(6, '-check.f "testIntegrationSerialSuite"')	
                }
                tests["Race Test Chunk #6 planner/core-testIntegrationSuite"] = {
                    run_race_test_heavy_with_args(6, '-check.f "testIntegrationSuite"')	
                }
                tests["Race Test Chunk #6 planner/core-testPlanSuite"] = {
                    run_race_test_heavy_with_args(6, '-check.f "testPlanSuite"')	
                }
                tests["Race Test Chunk #6 planner/core-other suite"] = {
                    run_race_test_heavy_with_args(6, '-check.exclude "testPlanSuite|testIntegrationSuite|testIntegrationSerialSuite"')
                }
                tests["Race Test Chunk #8 session"] = {
                    run_race_test_heavy_parallel(8)
                }

            } else {
                tests["Race Test Chunk #7 ddl"] = {
                    run_race_test_heavy(7)
                }

                tests["Race Test Chunk #6 planner/core"] = {
                    run_race_test_heavy(6)
                }

                tests["Race Test Chunk #8 session"] = {
                    run_race_test_heavy(8)
                }
            }

            tests["Race Test Chunk #12 expression"] = {
                run_race_test(12)
            }

            tests["Race Test Chunk #1"] = {
                run_race_test(1)
            }

            tests["Race Test Chunk #2"] = {
                run_race_test(2)
            }

            tests["Race Test Chunk #3"] = {
                run_race_test(3)
            }

            tests["Race Test Chunk #4"] = {
                run_race_test(4)
            }

            tests["Race Test Chunk #5"] = {
                run_race_test(5)
            }

            tests["Race Test Chunk #13"] = {
                run_race_test(13)
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
        node(buildSlave){
            container("golang"){
                sh """
		    echo "done" > done
		    curl -F ci_check/tidb/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
            }
        }
    }
}
catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/unit-test'),
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
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis - waitBuildDone) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Unit Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "SUCCESS" && duration >= 3 && ghprbTargetBranch == "master") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    // if (currentBuild.result != "SUCCESS") {
    // slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // }

    if (currentBuild.result == "SUCCESS" && ghprbTargetBranch == "master") {
        build job: 'extract_unittest_log', wait: false, parameters: [[$class: 'StringParameterValue', name: 'BUILD_NUMBER', value: "${BUILD_NUMBER}"]]
    }
}
