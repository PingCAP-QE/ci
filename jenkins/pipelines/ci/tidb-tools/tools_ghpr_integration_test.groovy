def TIKV_BRANCH = "master"
def PD_BRANCH = "master"
def TIDB_BRANCH = "master"

if (params.containsKey("release_test")) {
    echo "this build is triggered by qa for release testing"
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tools_commit)
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
    TIDB_BRANCH = ghprbTargetBranch
    TIKV_BRANCH = ghprbTargetBranch
    PD_BRANCH = ghprbTargetBranch
}

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
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
// parse tidb branch
def m3 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

GO_VERSION = "go1.20"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.20:latest"
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
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-upgrade-temporary.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
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
                // tidb-tools
                dir("/home/jenkins/agent/git/tidb-tools") {
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        try {
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-tools.git']]]
                        } catch (error) {
                            retry(2) {
                                echo "checkout failed, retry.."
                                sleep 60
                                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb-tools.git']]]
                            }
                        }
                    }

                dir("go/src/github.com/pingcap/tidb-tools") {
                    sh """
                        cp -R /home/jenkins/agent/git/tidb-tools/. ./
                        git checkout -f ${ghprbActualCommit}
                    """
                }

                stash includes: "go/src/github.com/pingcap/tidb-tools/**", name: "tidb-tools", useDefaultExcludes: false

                // tikv
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz | tar xz"
                // pd
                def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz | tar xz"
                // tidb
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                sh "curl -C - --retry 3 ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz | tar xz"

                // tools
                sh "curl https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/importer bin/"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/"
                sh "mv tidb-enterprise-tools-nightly-linux-amd64/bin/mydumper bin/"
                sh "rm -r tidb-enterprise-tools-nightly-linux-amd64"

                stash includes: "bin/**", name: "binaries"

                sh "ls -l ./"
                sh "ls -l ./bin"
            }
        }
    }

    stage('Integration Test') {
        def tests = [:]
        def label = "tools-integration-${BUILD_NUMBER}"

        tests["Integration Test"] = {
            podTemplate(label: label, nodeSelector: "role_type=slave", containers: [
            containerTemplate(name: 'golang',alwaysPullImage: true,image: "${POD_GO_IMAGE}", ttyEnabled: true, command: 'cat'),
            containerTemplate(
                name: 'mysql',
                image: 'hub.pingcap.net/jenkins/mysql:5.7',
                ttyEnabled: true,
                alwaysPullImage: false,
                envVars: [
                    envVar(key: 'MYSQL_ALLOW_EMPTY_PASSWORD', value: '1'),
                ],
                args: '--log-bin --binlog-format=ROW --server-id=1',
            )
            ]) {
                node(label) {
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        unstash "tidb-tools"
                        unstash 'binaries'
                        dir("go/src/github.com/pingcap/tidb-tools") {
                            sh "mv ${ws}/bin ./bin/"
                            try {
                               sh """
                               for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                                export MYSQL_HOST="127.0.0.1"
                                export MYSQL_PORT=3306
                                make integration_test
                                """
                            } catch (Exception e) {
                                sh """
                                for filename in `ls /tmp/tidb_tools_test/*/*.log`; do
                                    echo "**************************************"
                                    echo "\$filename"
                                    cat "\$filename"
                                done
                                echo "******************sync_diff.log********************"
                                cat /tmp/tidb_tools_test/sync_diff_inspector/output/sync_diff.log
                                echo "********************fix.sql********************"
                                ls /tmp/tidb_tools_test/sync_diff_inspector/output/fix-on-tidb
                                for filename in `ls /tmp/tidb_tools_test/sync_diff_inspector/output/fix-on-tidb | grep sql`; do
                                    echo "**************************************"
                                    echo "\$filename"
                                    cat "\$filename"
                                done
                                """
                                throw e;
                            }
                        }
                    }
                }
            }
        }

        parallel tests
    }

    currentBuild.result = "SUCCESS"
}

