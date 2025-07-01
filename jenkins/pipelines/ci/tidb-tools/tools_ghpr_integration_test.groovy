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

GO_VERSION = "go1.23"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.23:latest"
POD_LABEL = "${JOB_NAME}-${BUILD_NUMBER}-go123"

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-v2.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')

    GO_VERSION = "go1.23"
    POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.23:latest"
    POD_LABEL = "${JOB_NAME}-${BUILD_NUMBER}-go123"

    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
    println "pod label: ${POD_LABEL}"
}

def run_with_pod(Closure body) {
    def label = POD_LABEL
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
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
                // pd
                def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
                // tidb
                def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
                def dumpling_sha1 = tidb_sha1

                sh label: "download binaries", script: """
                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "tikv-server.tar.gz" "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
                tar -xz 'bin/*' -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz
                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "pd-server.tar.gz" "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                tar -xz 'bin/*' -f pd-server.tar.gz && rm -rf pd-server.tar.gz
                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "tidb-server.tar.gz" "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
                tar -xz 'bin/*' -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O "dumpling.tar.gz" "${FILE_SERVER_URL}/download/builds/pingcap/dumpling/${dumpling_sha1}/centos7/dumpling.tar.gz"
                tar -xz 'bin/dumpling' -f dumpling.tar.gz && rm -rf dumpling.tar.gz
                which bin/tikv-server
                which bin/pd-server
                which bin/tidb-server
                which bin/dumpling
                which bin/importer
                ls -alh bin/
                """

                // tools
                sh label: "download enterprise-tools-nightly", script: """
                curl https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz
                mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/
                rm -r tidb-enterprise-tools-nightly-linux-amd64
                """
                stash includes: "bin/**", name: "binaries"
                sh label: "show binaries", script: """
                ls -alh ./
                ls -alh ./bin/
                chmod +x bin/*
                ./bin/dumpling --version
                ./bin/tikv-server -V
                ./bin/pd-server -V
                ./bin/tidb-server -V
                """
            }
        }
    }

    stage('Integration Test') {
        def tests = [:]
        def label = "tools-integration-${BUILD_NUMBER}"

        tests["Integration Test"] = {
            podTemplate(label: label, nodeSelector: "kubernetes.io/arch=amd64", containers: [
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
