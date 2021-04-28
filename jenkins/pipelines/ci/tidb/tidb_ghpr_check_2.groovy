def notRun = 1
def buildSlave = "${GO_BUILD_SLAVE}"


def slackcolor = 'good'
def githash

def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

//def buildSlave = "${GO_BUILD_SLAVE}"
def testSlave = "${GO_TEST_SLAVE}"

def TIKV_BRANCH = params.getOrDefault("ghprbTargetBranch","")
def PD_BRANCH = params.getOrDefault("ghprbTargetBranch","")

if (params.containsKey("release_test")) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
    specStr = "+refs/heads/*:refs/remotes/origin/*"
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

try {
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

    node(buildSlave) {
        def ws = pwd()
        stage("debuf info"){
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "work space path:\n${ws}"
        }

        stage("Checkout") {

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
            stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
        }



        def tests = [:]
        tests["Build & Test"] = {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(15) {
                        sh """
                        package_base=`grep module go.mod | head -n 1 | awk '{print \$2}'`
                        sed -i  's,go list ./...| grep -vE "cmd",go list ./...| grep -vE "cmd" | grep -vE "store/tikv\$\$",' ./Makefile
                        sed -i "s,-cover \\\$(PACKAGES),-cover \${package_base}/store/tikv \\\$(PACKAGES)," ./Makefile
                        # export GOPROXY=http://goproxy.pingcap.net
                        if [ \"${ghprbTargetBranch}\" == \"master\" ]  ;then EXTRA_TEST_ARGS='-timeout 9m'  make test_part_2 ; fi

                        # if grep -q gogenerate "Makefile";then  make gogenerate ; fi
                        """
                    }
                }
            }
        }


        if (ghprbTargetBranch == "master"){
            tests["test session with real tikv"] = {
                node(testSlave) {
                    deleteDir()
                    unstash 'tidb'
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    container("golang") {
                        timeout(5) {
                            ws = pwd()
                            def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                            def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                            tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                            def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                            def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                            pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                            try{
                                dir("go/src/github.com/pingcap/tidb") {
                                    sh"""
                                    set +e
                                    killall -9 -r -q tikv-server
                                    killall -9 -r -q pd-server
                                    set -e
                        
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin
                                    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://127.0.0.1:2379 --peer-urls=http://127.0.0.1:2378 -force-new-cluster &> pd1.log &
                                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -f  tikv1.log &
            
                                    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://127.0.0.1:2389 --peer-urls=http://127.0.0.1:2388 -force-new-cluster &>  pd2.log &
                                    bin/tikv-server --pd=127.0.0.1:2389 -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -f  tikv2.log &
        
                                    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://127.0.0.1:2399 --peer-urls=http://127.0.0.1:2398 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --pd=127.0.0.1:2399 -s tikv3 --addr=0.0.0.0:20190 --advertise-addr=127.0.0.1:20190 --advertise-status-addr=127.0.0.1:20185 -f  tikv3.log &
            
                                    make failpoint-enable
                                    cd session
                                    export log_level=error
                                    # export GOPROXY=http://goproxy.pingcap.net
                                    go test -with-tikv -pd-addrs=127.0.0.1:2379,127.0.0.1:2389,127.0.0.1:2399 -timeout 10m -vet=off -check.p
                                    #go test -with-tikv -pd-addrs=127.0.0.1:2379 -timeout 10m -vet=off
                                    """
                                }
                            }catch (Exception e){
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/pd1.log || true"
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/tikv1.log || true"
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/pd2.log || true"
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/tikv2.log || true"
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/pd3.log || true"
                                sh "cat ${ws}/go/src/github.com/pingcap/tidb/tikv3.log || true"
                                throw e
                            }finally {
                                sh """
                                set +e
                                killall -9 -r -q tikv-server
                                killall -9 -r -q pd-server
                                set -e
                                """
                            }
                        }
                    }
                }
            }
        }
        parallel tests


        stage("Check go mod replace is removed") {
            dir("go/src/github.com/pingcap/tidb") {
                container("golang") {
                    timeout(10) {
                        sh """
                        if [ \"${ghprbTargetBranch}\" == \"master\" ] || [ \"${ghprbTargetBranch}\" == \"release-3.0\" ] || [ \"${ghprbTargetBranch}\" == \"release-3.1\"  ] ;then ./tools/check/check_parser_replace.sh ;fi
                        """
                    }
                }
            }
        }
        currentBuild.result = "SUCCESS"
        node(buildSlave){
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

}