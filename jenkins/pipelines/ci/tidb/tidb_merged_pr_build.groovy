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

def PLUGIN_BRANCH = ghprbTargetBranch
def pluginSpec = "+refs/heads/*:refs/remotes/origin/*"


// example hotfix branch  release-4.0-20210724 | example release-5.1-hotfix-tiflash-patch1
// remove suffix "-20210724", only use "release-4.0"
if (PLUGIN_BRANCH.startsWith("release-") && PLUGIN_BRANCH.split("-").size() >= 3 ) {
    def k = PLUGIN_BRANCH.indexOf("-", PLUGIN_BRANCH.indexOf("-") + 1)
    PLUGIN_BRANCH = PLUGIN_BRANCH.substring(0, k)
    println "tidb hotfix branch: ${ghprbTargetBranch}"
    println "plugin branch use ${PLUGIN_BRANCH}"
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"

def isBuildCheck = ghprbCommentBody && ghprbCommentBody.contains("/run-all-tests")

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

// branch
// master | hz-poc
// relase-4.0
// release-4.0-20210812
// release-5.1
// release-5.3
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
    GO_TEST_HEAVY_SLAVE = "test_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_HEAVY_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
println "GO_TEST_HEAVY_SLAVE=${GO_TEST_HEAVY_SLAVE}"

try {
    node("${GO_TEST_HEAVY_SLAVE}") {
        def ws = pwd()
        //deleteDir()

        stage("debuf info"){
            println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
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
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption',  timeout: 5]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                    }   catch (info) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 5
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                // if checkout one pr failed, we fallback to fetch all thre pr data
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 5]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                            }
                    }
                    container("golang") {
                        timeout(5) {
                            sh """
                            git checkout -f ${ghprbActualCommit}
                            mkdir -p ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                            cp -R ./* ${ws}/go/src/github.com/pingcap/tidb-build-plugin/
                            """
                        }
                    }
                }
            }
        }

        stage("Build tidb-server and plugin"){
            def builds = [:]
            builds["Build and upload TiDB"] = {
                stage("Build"){
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                sh """
                                nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
                                nohup bash -c "if WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &
                                make
                                """
                                waitUntil{
                                    (fileExists('importer.done') || fileExists('importer.fail')) && (fileExists('tidb-server-check.done') || fileExists('tidb-server-check.fail'))
                                }
                                sh """
                                ls bin
                                """
                                if (fileExists('importer.fail') ){
                                    sh """
                                    cat importer.log
                                    exit 1
                                    """
                                }
                                if (fileExists('tidb-server-check.fail') ){
                                    sh """
                                    cat tidb-server-check.log
                                    exit 1
                                    """
                                }
                            }
                        }
                    }
                }

                stage("Upload") {
                    def filepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                    def donepath = "builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
                    def refspath = "refs/pingcap/tidb/pr/${ghprbPullId}/sha1"
                    if (params.containsKey("triggered_by_upstream_ci")) {
                        refspath = "refs/pingcap/tidb/pr/branch-${ghprbTargetBranch}/sha1"
                    }

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                sh """
                                rm -rf .git
                                tar czvf tidb-server.tar.gz ./*
                                curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                echo "pr/${ghprbActualCommit}" > sha1
                                echo "done" > done
                                # sleep 1
                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload
                                curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                                """
                            }
                        }
                    }

                    filepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
                    donepath = "builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                sh """
                                curl -F ${filepath}=@tidb-server.tar.gz ${FILE_SERVER_URL}/upload
                                curl -F ${donepath}=@done ${FILE_SERVER_URL}/upload									
                                """
                                // archiveArtifacts artifacts: 'tidb-server.tar.gz', fingerprint: true
                            }
                        }
                    }
                }
            }

            builds["Build plugin"] = {
                if (ghprbTargetBranch == "master" || (ghprbTargetBranch.startsWith("release") &&  ghprbTargetBranch != "release-2.0" && ghprbTargetBranch != "release-2.1")) {
                    stage ("Build plugins") {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb-build-plugin") {
                                timeout(20) {
                                    sh """
                                    cd cmd/pluginpkg
                                    go build
                                    """
                                }
                            }
                            dir("go/src/github.com/pingcap/enterprise-plugin") {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PLUGIN_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 5]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: pluginSpec, url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                                println "plugin branch: ${PLUGIN_BRANCH}"
                                println "plugin commit id: ${githash}"
                            }
                            dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                                sh """
                                GO111MODULE=on go mod tidy
                                ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                                """
                            }
                            dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                                sh """
                                GO111MODULE=on go mod tidy
                                ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                                """
                            }
                        }
                    }
                }

            }
            parallel builds
        }

        // load plugin test
        // branch master and more recents branches after 2.1
        if ((ghprbTargetBranch == "master") || (ghprbTargetBranch.startsWith("release") &&  ghprbTargetBranch != "release-2.0" && ghprbTargetBranch != "release-2.1")) {
            stage("Loading Plugin test"){
                println "current target branch: ${ghprbTargetBranch}, start load plugin tests"
                dir("go/src/github.com/pingcap/tidb"){
                    container("golang") {
                        try{
                            sh"""
                            rm -rf /tmp/tidb
                            rm -rf plugin-so
                            mkdir -p plugin-so

                            cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit/audit-1.so ./plugin-so/
                            cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist/whitelist-1.so ./plugin-so/
                            ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server -plugin-dir=${ws}/go/src/github.com/pingcap/tidb/plugin-so -plugin-load=audit-1,whitelist-1 > /tmp/loading-plugin.log 2>&1 &

                            sleep 5
                            for i in 1 2 3; do mysql -h 127.0.0.1 -P 4000 -u root -e "select tidb_version()"  && break || sleep 5; done
                            """
                        }catch (error){
                            println "load plugin test 3 times failed, start tidb-server failed\n"
                            println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} -c golang bash"
                            println "work space path:\n${ws}"
                            sh"""
                            cat /tmp/loading-plugin.log
                            """
                            throw error
                        }finally{
                            sh"""
                            set +e
                            killall -9 -r tidb-server
                            set -e
                            """
                        }
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
}catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/plugin-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
