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

def slackcolor = 'good'
def githash

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
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

try {
    stage("Pre-check") {
        if (!params.force) {
            node("${GO_BUILD_SLAVE}") {
                container("golang") {
                    notRun = sh(returnStatus: true, script: """
    			    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
    			    """)
                }
            }
        }

        if (notRun == 0) {
            println "the ${ghprbActualCommit} has been tested"
            throw new RuntimeException("hasBeenTested")
        }
    }
    // def buildSlave = "test_go_heavy"
    def buildSlave = "${GO_BUILD_SLAVE}"
    def testSlave = "${GO_TEST_SLAVE}"
    node(buildSlave) {

        def ws = pwd()
        //deleteDir()

        stage("debuf info") {
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
        }

        stage("Build & Test") {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(30) {
                        sh """
                        git checkout -f ${ghprbActualCommit}
                        """
                    }
                    try {
                        if (ghprbTargetBranch == "master") {
                            def builds = [:]
                            builds["check"] = {
                                sh "make check"
                            }
                            builds["test_part_1"] = {
                                try {
                                    sh "make test_part_1"
                                } catch (err) {
                                    throw err
                                } finally {
                                    sh "cat cmd/explaintest/explain-test.out || true"
                                }
                            }
                            parallel builds
                        } else {
                            sh "make dev"
                        }
                    } catch (err) {
                        throw err
                    } finally {
                        sh "cat cmd/explaintest/explain-test.out || true"
                    }
                }
            }
        }

        stage("Check go mod replace is removed") {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        sh """
                        if [ \"${ghprbTargetBranch}\" == \"master\" ] ;then ./tools/check/check_parser_replace.sh ;fi
                        """
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            sh """
		    echo "done" > done
		    curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
		    """
        }
    }
}
// refer to http://maven.40175.n5.nabble.com/maven-surefire-branch-1506-updated-88c9cc2-gt-6909a37-td5929164.html
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

stage("upload status") {
    node("master") {
        println currentBuild.result
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/check_dev'),
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
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Build Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "SUCCESS" && duration >= 3 && ghprbTargetBranch == "master" && currentBuild.result == "SUCCESS") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}