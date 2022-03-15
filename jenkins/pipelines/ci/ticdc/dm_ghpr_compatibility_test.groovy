def TIDB_BRANCH = "master"
def BUILD_NUMBER = "${env.BUILD_NUMBER}"

def PRE_COMMIT = "${ghprbTargetBranch}"
def MYSQL_ARGS = "--log-bin --binlog-format=ROW --enforce-gtid-consistency=ON --gtid-mode=ON --server-id=1 --default-authentication-plugin=mysql_native_password"
def TEST_CASE = ""
def BREAK_COMPATIBILITY = "false"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

println "comment body=${ghprbCommentBody}"

// if this PR breaks compatibility
def m0 = ghprbCommentBody =~ /break_compatibility\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m0) {
    BREAK_COMPATIBILITY = "${m0[0][1]}"
}
m0 = null
println "BREAK_COMPATIBILITY=${BREAK_COMPATIBILITY}"
if (BREAK_COMPATIBILITY == "true") {
    currentBuild.result = 'SUCCESS'
    return 0
}

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parser previous commit
def m2 = ghprbCommentBody =~ /pre_commit\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PRE_COMMIT = "${m2[0][1]}"
}
m2 = null
println "PRE_COMMIT=${PRE_COMMIT}"

// parser test case name
def m3 = ghprbCommentBody =~ /case\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TEST_CASE = "${m3[0][1]}"
}
m3 = null
println "TEST_CASE=${TEST_CASE}"

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
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
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
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

catchError {
    stage('Prepare') {
        run_with_pod {
            container("golang") {
                def ws = pwd()
                deleteDir()
                dir("/home/jenkins/agent/git/ticdc") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tiflow.git']]]
                    } catch (error) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 60
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tiflow.git']]]
                        }
                    }
                }

                dir("go/src/github.com/pingcap/tiflow") {
                    sh """
                        cp -R /home/jenkins/agent/git/ticdc/. ./
                        
                        echo "build binary with previous version"
                        git checkout -f ${PRE_COMMIT}
                        export PATH=$PATH:/nfs/cache/gopath/bin:/usr/local/go/bin
                        make dm_integration_test_build
                        mv bin/dm-master.test bin/dm-master.test.previous
                        mv bin/dm-worker.test bin/dm-worker.test.previous

                        echo "build binary with current version"
                        git checkout -f ${ghprbActualCommit}
                        make dm_integration_test_build
                        mv bin/dm-master.test bin/dm-master.test.current
                        mv bin/dm-worker.test bin/dm-worker.test.current
                    """
                }

                stash includes: "go/src/github.com/pingcap/tiflow/**", name: "ticdc", useDefaultExcludes: false

                def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                sh "curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"


                // binlogctl
                sh "curl http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz | tar xz"
                sh "curl http://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector bin/"
                //sh "curl https://download.pingcap.org/tidb-tools-test-linux-amd64.tar.gz | tar xz"
                //sh "mv tidb-enterprise-tools-latest-linux-amd64/bin/sync_diff_inspector bin/"
                sh "mv tidb-enterprise-tools-latest-linux-amd64/bin/mydumper bin/"
                sh "rm -r tidb-enterprise-tools-latest-linux-amd64 || true"
                sh "rm -r tidb-enterprise-tools-nightly-linux-amd64 || true"

                // use a new version of gh-ost to overwrite the one in container("golang") (1.0.47 --> 1.1.0)
                sh "curl -L https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz | tar xz"
                sh "mv gh-ost bin/"

                // minio
                sh 'curl -L http://fileserver.pingcap.net/download/minio.tar.gz | tar xz'
                sh 'mv minio bin/'
                
                stash includes: "bin/**", name: "binaries"
            }
        }
    }

    stage('Compatibility Tests') {
        def label = "dm-ghprb-compatibility-test-${BUILD_NUMBER}"
        if (GO_VERSION == "go1.13") {
            label = "dm-ghprb-compatibility-test-go1130-${BUILD_NUMBER}"
        }
        if (GO_VERSION = "go1.16") {
            label = "dm-ghprb-compatibility-test-go1160-${BUILD_NUMBER}"
        }
        if (GO_VERSION = "go1.18") {
            label = "dm-ghprb-compatibility-test-go1180-${BUILD_NUMBER}"
        }
        podTemplate(label: label,
                nodeSelector: 'role_type=slave',
                namespace: "jenkins-tidb",
                idleMinutes: 0,
                containers: [
                        containerTemplate(name: 'golang',alwaysPullImage: true, image: "${POD_GO_IMAGE}", ttyEnabled: true,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                command: 'cat'),
                        containerTemplate(
                                name: 'mysql',
                                image: 'hub.pingcap.net/jenkins/mysql:5.7',
                                ttyEnabled: true,
                                alwaysPullImage: false,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                envVars: [
                                        envVar(key: 'MYSQL_ROOT_PASSWORD', value: '123456'),
                                ],
                                args: "${MYSQL_ARGS}",
                        ),
                        // hub.pingcap.net/jenkins/mysql:5.7, registry-mirror.pingcap.net/library/mysql:8.0.21
                        containerTemplate(
                                name: 'mysql1',
                                image: 'hub.pingcap.net/jenkins/mysql:5.7',
                                ttyEnabled: true,
                                alwaysPullImage: true,
                                resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                                envVars: [
                                        envVar(key: 'MYSQL_ROOT_PASSWORD', value: '123456'),
                                        envVar(key: 'MYSQL_TCP_PORT', value: '3307')
                                ],
                                args: "${MYSQL_ARGS}",
                        )
                ], volumes:[
                emptyDirVolume(mountPath: '/tmp', memory: true),
                emptyDirVolume(mountPath: '/home/jenkins', memory: true)
        ]) {
            node(label) {
                container("golang") {
                    timeout(30) {
                        def ws = pwd()
                        deleteDir()
                        unstash "ticdc"
                        unstash "binaries"
                        dir("go/src/github.com/pingcap/tiflow") {
                            try {
                                sh """
                                rm -rf /tmp/dm_test
                                mkdir -p /tmp/dm_test
                                mkdir -p bin
                                mv ${ws}/bin/* bin
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                export MYSQL_HOST1=127.0.0.1
                                export MYSQL_PORT1=3306
                                export MYSQL_HOST2=127.0.0.1
                                export MYSQL_PORT2=3307
                                export PATH=/usr/local/go/bin:$PATH
                                export GOPATH=\$GOPATH:${ws}/go
                                make dm_compatibility_test CASE=${TEST_CASE}
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/dm_test
                                cp /tmp/dm_test/cov*out cov_dir
                                """
                            } catch (Exception e) {
                                sh """
                                    for log in `ls /tmp/dm_test/*/*/log/*.log`; do
                                        echo "-----------------\$log begin-----------------"
                                        cat "\$log"
                                        echo "-----------------\$log end-----------------"
                                    done
                                """
                                throw e;
                            } finally {
                                sh """
                                echo success
                                """
                            }
                        }
                        stash includes: "go/src/github.com/pingcap/tiflow/cov_dir/**", name: "integration-cov-${TEST_CASE}"
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}


stage('Summary') {
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Compatibility Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
