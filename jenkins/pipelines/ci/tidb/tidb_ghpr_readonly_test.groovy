specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

GO_VERSION = "go1.21"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.21:latest"
POD_LABEL = "${JOB_NAME}-${BUILD_NUMBER}-go121"

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-v2.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = goversion_lib.selectGoImage(ghprbTargetBranch)
    POD_LABEL = goversion_lib.getPodLabel(ghprbTargetBranch, JOB_NAME, BUILD_NUMBER)
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
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'ruby', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/centos7_ruby-2.6.3:latest", ttyEnabled: true,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                            command: '/bin/sh -c', args: 'cat',
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            timeout(time: 60, unit: 'MINUTES') {
               body()
            }
        }
    }
}



run_with_pod {
    def ws = pwd()

    stage("Checkout") {
        container("golang") {
            sh "whoami && go version"
        }
        // update code
        dir("/home/jenkins/agent/code-archive") {
            // delete to clean workspace in case of agent pod reused lead to conflict.
            deleteDir()
            dir("${ws}/go/src/github.com/pingcap/tidb") {
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                }   catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 10]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:pingcap/tidb.git']]]
                        }
                }
                container("golang") {
                    def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
                    timeout(5) {
                        sh """
                        git checkout -f ${ghprbActualCommit}
                        """
                    }
                }
            }
        }
    }

    stage("Test") {
        def tidb_path = "${ws}/go/src/github.com/pingcap/tidb"
        dir("go/src/github.com/pingcap/tidb") {
            container("golang") {
                def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${ghprbTargetBranch}/sha1"
                def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${ghprbTargetBranch}/sha1"
                def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                try {
                    sh """
                    make
                    curl ${tikv_url} | tar xz
                    curl ${pd_url} | tar xz bin

                    bin/pd-server -name=pd --data-dir=pd --client-urls=http://127.0.0.1:2379 &> pd.log &
                    sleep 20
                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 -f tikv.log &
                    sleep 20

                    bin/tidb-server -P 4001 -host 127.0.0.1 -store tikv -path 127.0.0.1:2379 -status 10081 &> tidb1.log &
                    bin/tidb-server -P 4002 -host 127.0.0.1 -store tikv -path 127.0.0.1:2379 -status 10082 &> tidb2.log &
                    sleep 20

                    cd tests/readonlytest
                    go mod tidy
                    go test
                    """
                }catch (Exception e) {
                    sh "cat ${ws}/go/src/github.com/pingcap/tidb/tidb1.log || true"
                    sh "cat ${ws}/go/src/github.com/pingcap/tidb/tidb2.log || true"
                    throw e
                }finally {
                    sh """
                    set +e
                    killall -9 -r tidb-server
                    killall -9 -r tikv-server
                    killall -9 -r pd-server
                    set -e
                    """
                }
            }
        }
    }
}
