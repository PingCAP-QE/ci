def slackcolor = 'good'
def githash

def PLUGIN_BRANCH = ghprbTargetBranch
// parse enterprise-plugin branch
def m1 = ghprbCommentBody =~ /plugin\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    PLUGIN_BRANCH = "${m1[0][1]}"
}
pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
// transfer plugin branch from pr/28 to origin/pr/28/head
if (PLUGIN_BRANCH.startsWith("pr/")) {
    pluginSpec = "+refs/pull/*:refs/remotes/origin/pr/*"
    PLUGIN_BRANCH = "origin/${PLUGIN_BRANCH}/head"
}

m1 = null
println "ENTERPRISE_PLUGIN_BRANCH=${PLUGIN_BRANCH}"

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def isBuildCheck = ghprbCommentBody && ghprbCommentBody.contains("/run-all-tests")

try {
    node("${GO_BUILD_SLAVE}") {
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
                    timeout(10) {
                        sh """
                            cp -R /nfs/cache/git/src-tidb.tar.gz ./
                            cp -R /nfs/cache/git/src-tidb.tar.gz.md5sum ./
                        """
                    }
                    timeout(6) {
                        sh """
                            mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                            tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb/ --strip-components=1
                        """
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tidb"
                        echo "Clean dir then get tidb src code from fileserver"
                        deleteDir()
                    }
                    if(!fileExists("${ws}/go/src/github.com/pingcap/tidb/Makefile")) {
                        dir("/home/jenkins/agent/code-archive") {
                            sh """
                            rm -rf tidb.tar.gz
                            rm -rf tidb
                            wget ${FILE_SERVER_URL}/download/source/tidb.tar.gz
                            tar -xzf tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb/ --strip-components=1
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
                                if (isBuildCheck){
                                    sh """
	                                nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
	                                nohup bash -c "if WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &
	                                make
	                                """
                                }else{
                                    sh """
	                                nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
	                                nohup bash -c "if  WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &	                                
	                                make
	                                touch tidb-server-check.done
	                                """
                                }

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
                                // archiveArtifacts artifacts: 'tidb-server.tar.gz', fingerprint: true
                            }
                        }
                    }
//                    change  isBuildCheck to true , always run
                    if(true){
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
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PLUGIN_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: pluginSpec, url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                            }
                            dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                                sh """
                               ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                               """
                            }
                            dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                                sh """
                               ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                               """
                            }
                        }
                    }
                }

            }
            parallel builds
        }

        if ((ghprbTargetBranch == "master") || (ghprbTargetBranch.startsWith("release") &&  ghprbTargetBranch != "release-2.0" && ghprbTargetBranch != "release-2.1")) stage("Loading Plugin test"){
            dir("go/src/github.com/pingcap/tidb"){
                container("golang") {
                    try{
                        sh"""
                    rm -rf /tmp/tidb
                    mkdir -p plugin-so
                    cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit/audit-1.so ./plugin-so/
                    cp ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist/whitelist-1.so ./plugin-so/
                    ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server -plugin-dir=${ws}/go/src/github.com/pingcap/tidb/plugin-so -plugin-load=audit-1,whitelist-1 > /tmp/loading-plugin.log 2>&1 &

                    sleep 5
                    mysql -h 127.0.0.1 -P 4000 -u root -e "select tidb_version()"
                    """
                    }catch (error){
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
stage("upload status"){
    node{
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
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

    if (duration >= 3 && ghprbTargetBranch == "master" && currentBuild.result == "SUCCESS") {
        slackSend channel: '#jenkins-ci-3-minutes', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
}