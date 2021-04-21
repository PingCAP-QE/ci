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

if (ghprbTargetBranch != "master") {
    return
}

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
            // update cache
            dir("/home/jenkins/agent/git/tidb") {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                if(!fileExists("/home/jenkins/agent/git/tidb/Makefile")) {
                    dir("/home/jenkins/agent/git") {
                        sh """
                            rm -rf tidb.tar.gz
                            rm -rf tidb
                            wget ${FILE_SERVER_URL}/download/source/tidb.tar.gz
                            tar xvf tidb.tar.gz
                        """
                    }
                }
            }
            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    timeout(5) {
                        sh """
                        cp -R /home/jenkins/agent/git/tidb/* ./
                        """
                    }
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        } catch (info) {
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

        stage("Build tidb-server and e2e"){
            def builds = [:]
            builds["Test"] = {
                stage("Build"){
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                try {
                                    sh """
                                    cd tests/graceshutdown
                                    make
                                    sh run-tests.sh
                                    """
                                } catch (err) {
                                    sh "cat /tmp/tidb_gracefulshutdown/tidb5501.log"
                                    throw err
                                }
                                
                                sh """
                                
                                cd tests/globalkilltest
                                tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/master/sha1"`
	                            tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"
	
	                            pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/master/sha1"`
	                            pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"
	
	
	                            while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
	                            curl \${tikv_url} | tar xz bin
	
	                            while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
	                            curl \${pd_url} | tar xz bin
	                            ls -lhrt ./bin
                                make
                                ls -lhrt ./bin
                                PD=./bin/pd-server  TIKV=./bin/tikv-server sh run-tests.sh
                                """
                            }
                        }
                    }
                }
            }

            parallel builds
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