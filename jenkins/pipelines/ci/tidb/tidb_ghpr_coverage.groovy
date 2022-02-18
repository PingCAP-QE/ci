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
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"


def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def slackcolor = 'good'
def githash

try {

    def buildSlave = "${GO_BUILD_SLAVE}"

    node(buildSlave) {
        def ws = pwd()
        //deleteDir()

        stage("debuf info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "work space path:\n${ws}"
        }

        stage("Checkout") {
            container("golang") {
                sh "whoami && go version"
            }
            // update code
            dir("/home/jenkins/agent/code-archive") {
                // delete to clean workspace in case of agent pod reused lead to conflict.
                deleteDir()
                // copy code from nfs cache
                container("golang") {
                    if(fileExists("/home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz")){
                        timeout(5) {
                            sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tidb.tar.gz*  ./
                                mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                            """
                        }
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                    }   catch (info) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 5
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                            }
                    }
                    container("golang") {
                        def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
                        timeout(5) {
                            sh """
                            git checkout -f ${ghprbActualCommit}
                            """
                            sh """
                            sed -ir "s:-project=github.com/pingcap/tidb:-project=${tidb_path}:g" Makefile
                            """
                        }
                    }
                }
            }
        }


        stage("Test & Coverage") {
            def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    if (ghprbTargetBranch in ["master", "release-5.4"]) {
                        sh """
                        export log_level=warn
                        make br_unit_test_in_verify_ci
                        mv test_coverage/br_cov.unit_test.out br.coverage
                        make dumpling_unit_test_in_verify_ci
                        mv test_coverage/dumpling_cov.unit_test.out dumpling.coverage
                        make gotest_in_verify_ci
                        mv test_coverage/tidb_cov.unit_test.out tidb.coverage
                        """
                    } else if (ghprbTargetBranch in ["release-5.1", "release-5.2"]) {
                        sh """
                        make gotest
                        make br_unit_test
                        make dumpling_unit_test
                        """
                    } else {
                        sh """
                        make test
                        """
                    }

                    withCredentials([string(credentialsId: 'codecov-token-tidb', variable: 'CODECOV_TOKEN'),
                                    string(credentialsId: 'codecov-api-token', variable: 'CODECOV_API_TOKEN')]) {
                        timeout(30) {
                            if (ghprbTargetBranch in ["master", "release-5.4"]) { 
                                if (ghprbPullId != null && ghprbPullId != "") {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "tidb.coverage" -f "br.coverage" -f "dumpling.coverage" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -P ${ghprbPullId} -b ${BUILD_NUMBER} 
                                    """
                                } else {
                                    sh """
                                    curl -LO ${FILE_SERVER_URL}/download/cicd/ci-tools/codecov
                                    chmod +x codecov
                                    ./codecov -f "tidb.coverage" -f "br.coverage" -f "dumpling.coverage" -t ${CODECOV_TOKEN} -C ${ghprbActualCommit} -b ${BUILD_NUMBER} -B ${ghprbTargetBranch}
                                    """
                                }

                                // wait until codecov upload finish
                                sleep(time:100,unit:"SECONDS")
                                def response = httpRequest Authorization: CODECOV_API_TOKEN, url: "https://codecov.io/api/gh/pingcap/tidb/commit/${ghprbActualCommit}"
                                println('Status: '+response.status)
                                def obj = readJSON text:response.content
                                if (response.status == 200) {
                                    println(obj.commit.totals)
                                    currentBuild.description = "Lines coverage: ${obj.commit.totals.c.toFloat().round(2)}%"
                                    println('Coverage: '+obj.commit.totals.c)
                                    println("Files count: "+ obj.commit.totals.f)
                                    println("Lines count: "+obj.commit.totals.n)
                                    println("Hits count: "+obj.commit.totals.h)
                                    println("Misses count: "+obj.commit.totals.m)
                                    println("Paritials count: "+obj.commit.totals.p)

                                    println('Coverage: '+obj.commit.totals.diff[5])
                                    println("Files count: "+ obj.commit.totals.diff[0])
                                    println("Lines count: "+obj.commit.totals.diff[1])
                                    println("Hits count: "+obj.commit.totals.diff[2])
                                    println("Misses count: "+obj.commit.totals.diff[3])
                                    println("Paritials count: "+obj.commit.totals.diff[4])
                                } else {
                                    println('Error: '+response.content)
                                    println('Status not 200: '+response.status)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    currentBuild.description = "unit test failed, coverage data was not available"
    slackcolor = 'danger'
    echo "${e}"
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/code-coverage'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
