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

// example hotfix branch  release-4.0-20210724 | example release-5.1-hotfix-tiflash-patch1
// remove suffix "-20210724", only use "release-4.0"
if (PLUGIN_BRANCH.startsWith("release-") && PLUGIN_BRANCH.split("-").size() >= 3 ) {
    def k = PLUGIN_BRANCH.indexOf("-", PLUGIN_BRANCH.indexOf("-") + 1)
    PLUGIN_BRANCH = PLUGIN_BRANCH.substring(0, k)
    println "tidb hotfix branch: ${ghprbTargetBranch}"
    println "plugin branch use ${PLUGIN_BRANCH}"
}
if (ghprbTargetBranch == "6.1.0-pitr-dev") {
    PLUGIN_BRANCH = "release-6.1"
}

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

GO_VERSION = "go1.18"
ALWAYS_PULL_IMAGE = true
RESOURCE_REQUEST_CPU = '4000m'
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "bazel_master": "hub.pingcap.net/wangweizhen/tidb_image:20220802",
]
VOLUMES = [
    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
    emptyDirVolume(mountPath: '/tmp', memory: false),
]

def user_bazel(branch) {
    if (branch in ["master"]) {
        return true
    }
    if (branch.startsWith("release-") && branch >= "release-6.2") {
        return true
    }
    return false
}

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    if (user_bazel(ghprbTargetBranch)) {
        GO_VERSION = "bazel_master"
        ALWAYS_PULL_IMAGE = false
        RESOURCE_REQUEST_CPU = '2000m'
    } else {
        GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
        VOLUMES.add(emptyDirVolume(mountPath: '/home/jenkins', memory: false))
    }
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

def run_with_pod(Closure body) {
    def label = "tidb-ghpr-build-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: ALWAYS_PULL_IMAGE,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: RESOURCE_REQUEST_CPU, resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
                    )
            ],
            volumes: VOLUMES,
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "go image: ${POD_GO_IMAGE}"
            body()
        }
    }
}

try {
    run_with_pod {
        def ws = pwd()
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
                                wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/cicd/daily-cache-code/tidb.tar.gz -q --show-progress
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
                                    if (user_bazel(ghprbTargetBranch))  {
                                        sh """
                                        if make bazel_build; then
                                            touch importer.done
                                            touch tidb-server-check.done
                                        else 
                                            touch importer.fail
                                            touch tidb-server-check.fail
                                            exit 1
                                        fi
                                        """
                                    } else {
                                        sh """
                                        nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
                                        nohup bash -c "if WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &
                                        make
                                        """
                                    }
                                }else{
                                    if (user_bazel(ghprbTargetBranch))  {
                                        sh """
                                        if make bazel_build; then
                                            touch importer.done
                                            touch tidb-server-check.done
                                        else 
                                            touch importer.fail
                                            touch tidb-server-check.fail
                                        fi
                                        touch tidb-server-check.done
                                        """
                                    } else {
                                        sh """
                                        nohup bash -c "if make importer ;then touch importer.done;else touch importer.fail; fi"  > importer.log &
                                        nohup bash -c "if  WITH_CHECK=1 make TARGET=bin/tidb-server-check ;then touch tidb-server-check.done;else touch tidb-server-check.fail; fi" > tidb-server-check.log &                                    
                                        make
                                        touch tidb-server-check.done
                                        """
                                    }
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
} catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
} finally {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'REPO', value: "${ghprbGhRepository}"],
                    [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
                    [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
                    [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: ghprbPullAuthorLogin],
                    [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: ghprbPullAuthorLogin],
                    [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: ghprbPullAuthorLogin],
                    [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
                    [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
                    [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: ""],
                    [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: ""],
                    [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
        ]
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
