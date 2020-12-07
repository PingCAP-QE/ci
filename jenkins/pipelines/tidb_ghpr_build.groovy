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
                dir("/home/jenkins/agent/git/tidb") {
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                    } catch (error) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 20
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        }
                    }
                }
            }
        }

        stage("Build tidb-server and plugin"){
            container("golang") {
                sh "mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod"
            }
            def builds = [:]
            builds["Build and upload TiDB"] = {
                stage("Build"){
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            deleteDir()
                            timeout(10) {
                            	sh """
                                cp -R /home/jenkins/agent/git/tidb/. ./
                                git checkout -f ${ghprbActualCommit}
                                """                            	
                            	if (isBuildCheck){
	                                sh """
	                                # export GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn
	                                # 为了防止 waitUntil 一直检测输出太多 pipeline log，选择将 tidb-server 放在前台编译，importer 和 tidb-server-check 放在后台并行编译
	                                # 为了减少 build 占用资源，只在 /run-all-tests 的时候 build check
	                                nohup bash -c "if GOPATH=${ws}/go  make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
	                                nohup bash -c "if GOPATH=${ws}/go  WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &
	                                GOPATH=${ws}/go  make
	                                """                            	    
                            	}else{
	                                sh """
	                                # export GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn
	                                # 为了防止 waitUntil 一直检测输出太多 pipeline log，选择将 tidb-server 放在前台编译，importer 和 tidb-server-check 放在后台并行编译
	                                # 为了减少 build 占用资源，只在 /run-all-tests 的时候 build check
	                                nohup bash -c "if GOPATH=${ws}/go  make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
	                                # nohup bash -c "if GOPATH=${ws}/go  WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &	                                
	                                GOPATH=${ws}/go  make
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
                    if(isBuildCheck){
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
                                deleteDir()
                                timeout(20) {
                                    sh """
                                    cp -R /home/jenkins/agent/git/tidb/. ./
                                    git checkout -f ${ghprbActualCommit}
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
                               GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                               """
                           }
                           dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                               sh """
                               GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
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
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
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
