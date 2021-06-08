if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__binlog_commit)
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch

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
// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], ghprbTargetBranch)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/pingcap/centos7_golang-1.16:latest"
} else {
    println "This build use go1.13"
    POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.13:cached"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Prepare') {
        node ("${GO_BUILD_SLAVE}") {
            container("golang") {
                def ws = pwd()
                deleteDir()
                // tidb-binlog
  
                dir("${ws}/go/src/github.com/pingcap/tidb-binlog") {
                    container("golang") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 

                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        try {
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                        } catch (error) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 5
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                            }
                        }

                        sh "git checkout -f ${ghprbActualCommit}"
                    }
                }
               
                stash includes: "go/src/github.com/pingcap/tidb-binlog/**", name: "tidb-binlog", useDefaultExcludes: false

                // tikv
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"
                // pd
                def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                // tidb
                def tidb_sha1
                if (TIDB_BRANCH.startsWith("pr/")) {
                    def prID = TIDB_BRANCH.split("pr/")[1]
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/pr/${prID}/sha1").trim()
                } else {
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                }
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

        
                // binlogctl && sync_diff_inspector
                sh "curl https://download.pingcap.org/tidb-tools-v2.1.6-linux-amd64.tar.gz | tar xz"
                sh "mv tidb-tools-v2.1.6-linux-amd64/bin/binlogctl bin/"
                sh "mv tidb-tools-v2.1.6-linux-amd64/bin/sync_diff_inspector bin/"
                sh "rm -r tidb-tools-v2.1.6-linux-amd64 || true"

                stash includes: "bin/**", name: "binaries"
            }
        }
    }

    stage('Integration Test') {
        def tests = [:]

        def label = "binlog-integration-${UUID.randomUUID().toString()}"

        tests["Integration Test"] = {
            podTemplate(label: label, 
            idleMinutes: 0,
            containers: [
                containerTemplate(name: 'golang',alwaysPullImage: false, image: "${POD_GO_DOCKER_IMAGE}",
                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                ttyEnabled: true, command: 'cat'),
                containerTemplate(name: 'zookeeper',alwaysPullImage: false, image: 'wurstmeister/zookeeper', 
                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                ttyEnabled: true),
                containerTemplate(
                    name: 'kafka',
                    image: 'wurstmeister/kafka',
                    resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                    ttyEnabled: true,
                    alwaysPullImage: false,
                    envVars: [
                        envVar(key: 'KAFKA_MESSAGE_MAX_BYTES', value: '1073741824'),
                        envVar(key: 'KAFKA_REPLICA_FETCH_MAX_BYTES', value: '1073741824'),
                        envVar(key: 'KAFKA_ADVERTISED_PORT', value: '9092'),
                        envVar(key: 'KAFKA_ADVERTISED_HOST_NAME', value:'127.0.0.1'),
                        envVar(key: 'KAFKA_BROKER_ID', value: '1'),
                        envVar(key: 'ZK', value: 'zk'),
                        envVar(key: 'KAFKA_ZOOKEEPER_CONNECT', value: 'localhost:2181'),
                    ]
                )
            ], volumes:[
                emptyDirVolume(mountPath: '/tmp', memory: true),
                emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                ]) {
                node(label) {
                    println "debug node:\n ssh root@172.16.5.15"
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        unstash 'tidb-binlog'
                        unstash 'binaries'

                        dir("go/src/github.com/pingcap/tidb-binlog") {
                            sh "mv ${ws}/bin ./bin/"
                            try {
                                sh """
                                hostname
                                docker ps || true
                                KAFKA_ADDRS=127.0.0.1:9092 GOPATH=\$GOPATH:${ws}/go make integration_test
                                """
                            } catch (Exception e) {

                                sh "cat '/tmp/tidb_binlog_test/pd.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/tikv.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/tidb.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/drainer.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/pump_8250.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/pump_8251.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/reparo.log' || true"
                                sh "cat '/tmp/tidb_binlog_test/binlog.out' || true"
                                sh "cat '/tmp/tidb_binlog_test/kafka.out' || true"
                                throw e;
                            } finally {
                                sh """
                                echo success
                                """
                            }
                        }
                    }
                }
            }
        }

        parallel tests
    }

    currentBuild.result = "SUCCESS"
}catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
