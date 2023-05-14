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
def TIDB_TOOLS_BRANCH = ghprbTargetBranch

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

// parse tidb-tools branch
def m4 = ghprbCommentBody =~ /tidb-tools\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m4) {
    TIDB_TOOLS_BRANCH = "${m4[0][1]}"
}
m4 = null

if (TIDB_TOOLS_BRANCH.matches(/^(release-)?(6\.[0-9]|5\.[3-4]|7|8)\d*(\.\d+)?(\-.*)?$/)) {
    println "TIDB_TOOLS repo not exit branch ${TIDB_TOOLS_BRANCH}, use master instead"
    TIDB_TOOLS_BRANCH = "master"
}
println "TIDB_TOOLS_BRANCH=${TIDB_TOOLS_BRANCH}"

GO_VERSION = "go1.20"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
    "go1.20": "hub.pingcap.net/jenkins/centos7_golang-1.20:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
    "go1.20": "${JOB_NAME}-go1200-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/ci/tidb/goversion-select-lib.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kuberenetes-ksyun"
    def namespace = "jenkins-tidb-binlog"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}

specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}


try {
    stage('Prepare') {
        run_with_pod {
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
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-binlog.git']]]
                        } catch (error) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 5
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-binlog.git']]]
                            }
                        }
                        sh """
                        git checkout -f ${ghprbActualCommit}
                        make build
                        ls -l ./bin && mv ./bin ${ws}/bin
                        """
                    }
                }

                stash includes: "go/src/github.com/pingcap/tidb-binlog/**", name: "tidb-binlog", useDefaultExcludes: false

                // tikv
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"
                // pd
                def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                // tidb
                def tidb_sha1
                if (TIDB_BRANCH.startsWith("pr/")) {
                    def prID = TIDB_BRANCH.split("pr/")[1]
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/pr/${prID}/sha1").trim()
                } else {
                    tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                }
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

                dir("go/src/github.com/pingcap/tidb-tools") {
                    def tidb_tools_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-tools/${TIDB_TOOLS_BRANCH}/sha1").trim()
                    def tidb_tools_file = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/${tidb_tools_sha1}/centos7/tidb-tools.tar.gz"
                    sh """
                    curl ${tidb_tools_file} | tar xz
                    ls -l ./bin
                    rm -f bin/{ddl_checker,importer}
                    mv ${ws}/bin/* ./bin/
                    ls -l ./bin
                    """
                }

                stash includes: "go/src/github.com/pingcap/tidb-tools/bin/**", name: "binaries"
            }
        }
    }

    stage('Integration Test') {
        def tests = [:]
        def label = "binlog-integration-test-${BUILD_NUMBER}"
        if (GO_VERSION == "go1.13") {
            label = "binlog-integration-test-go1130-${BUILD_NUMBER}"
        }
        if (GO_VERSION == "go1.16") {
            label = "binlog-integration-test-go1160-${BUILD_NUMBER}"
        }
        if (GO_VERSION == "go1.18") {
            label = "binlog-integration-test-go1180-${BUILD_NUMBER}"
        }
        tests["Integration Test"] = {
            podTemplate(label: label,
            cloud: "kuberenetes-ksyun",
            namespace: "jenkins-tidb-binlog",
            idleMinutes: 0,
            containers: [
                containerTemplate(name: 'golang',alwaysPullImage: false, image: "${POD_GO_IMAGE}",
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
                            sh """
                            ls -l ${ws}/go/src/github.com/pingcap/tidb-tools/bin
                            mv ${ws}/go/src/github.com/pingcap/tidb-tools/bin ./bin
                            ls -l ./bin
                            """
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
