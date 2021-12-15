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


def TIDB_TEST_BRANCH = "master"
def TIDB_PRIVATE_TEST_BRANCH = "master"

def MYBATIS3_URL = "${FILE_SERVER_URL}/download/static/travis-tidb.zip"

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

// parse tidb_private_test branch
def m4 = ghprbCommentBody =~ /tidb[_\-]private[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIDB_PRIVATE_TEST_BRANCH = "${m4[0][1]}"
}
m4 = null
println "TIDB_PRIVATE_TEST_BRANCH=${TIDB_PRIVATE_TEST_BRANCH}"

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
    isNeedGo1160 = isBranchMatched(["master", "hz-poc", "ft-data-inconsistency", "br-stream"], ghprbTargetBranch)
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
all_task_result = []

try {
    stage('Mybatis Test') {
        node("test_java_memvolume") {
            try {
                container("java") {
                    def ws = pwd()
                    deleteDir()

                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    dir("go/src/github.com/pingcap/tidb") {
                        def url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                        def done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
                        timeout(10) {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            rm -rf /tmp/tidb
                            set -e

                            while ! curl --output /dev/null --silent --head --fail ${done_url}; do sleep 1; done
                            curl ${url} | tar xz
                            rm -f bin/tidb-server
                            rm -f bin/tidb-server-race
                            cp bin/tidb-server-check bin/tidb-server
                            cat > config.toml << __EOF__
[performance]
join-concurrency = 1
__EOF__

                            bin/tidb-server -config config.toml > ${ws}/tidb_mybatis3_test.log 2>&1 &
                            
                            """
                        }
                        if (!ghprbTargetBranch.startsWith("release-2")) {
                            retry(3) {
                                sh """
                                    sleep 5
                                    wget ${FILE_SERVER_URL}/download/mysql && chmod +x mysql
                                    mysql -h 127.0.0.1 -P4000 -uroot -e 'set @@global.tidb_enable_window_function = 0'
                                """
                            }
                        }
                    }

                    try {
                        dir("mybatis3") {
                            timeout(10) {
                                sh """
                                curl -L ${MYBATIS3_URL} -o travis-tidb.zip && unzip travis-tidb.zip && rm -rf travis-tidb.zip
                                cp -R mybatis-3-travis-tidb/. ./ && rm -rf mybatis-3-travis-tidb
                                mvn -B clean test
                                """
                            }
                        }
                    } catch (err) {
                        sh "cat ${ws}/tidb_mybatis3_test.log"
                        throw err
                    } finally {
                        sh "killall -9 -r tidb-server || true"
                    }
                }
                all_task_result << ["name": "Mybatis Test", "status": "success", "error": ""]
            } catch (err) {
                all_task_result << ["name": "Mybatis Test", "status": "failed", "error": err.message]
                throw err
            }
        }
    }

    currentBuild.result = "SUCCESS"
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
} finally {
    stage("task summary") {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            println "all_results: ${json}"
            currentBuild.description = "${json}"
        }
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/mybatis-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
