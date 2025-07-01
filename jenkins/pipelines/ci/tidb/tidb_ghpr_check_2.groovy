
def slackcolor = 'good'
def githash

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch

if (params.containsKey("release_test") && params.triggered_by_upstream_ci == null) {
    TIKV_BRANCH = params.release_test__tikv_commit
    PD_BRANCH = params.release_test__pd_commit
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

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""
ciErrorCode = 0

ALWAYS_PULL_IMAGE = true

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: ALWAYS_PULL_IMAGE,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            privileged: true,
                            resourceRequestCpu: '6000m', resourceRequestMemory: '8Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    ),
                    containerTemplate(
                            name: 'jnlp', image: "${jnlp_docker_image}", alwaysPullImage: ALWAYS_PULL_IMAGE,
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    // TODO use s3 cache instead of nfs
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/git', readOnly: false),
            ],
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
        stage("Checkout") {

            try {
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
                                        wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz -q --show-progress
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
                            sh """
                            GO111MODULE=on go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                            """
                        }
                    }
                }
            } catch (e) {
                println "Checkout failed: ${e}"
                ciErrorCode = 1
                throw e
            }
            stash includes: "go/src/github.com/pingcap/tidb/**", name: "tidb"
        }

        def tests = [:]
        tests["test_part_parser & gogenerate"] = {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(15) {
                        sh """
                        package_base=`grep module go.mod | head -n 1 | awk '{print \$2}'`
                        sed -i  's,go list ./...| grep -vE "cmd",go list ./...| grep -vE "cmd" | grep -vE "store/tikv\$\$",' ./Makefile

                        if [ \"${ghprbTargetBranch}\" == \"master\" ]  ;then EXTRA_TEST_ARGS='-timeout 9m'  make test_part_parser && make gogenerate ; fi > test.log || \\
                        (cat test.log; cat test.log |grep -Ev "^\\[[[:digit:]]{4}(/[[:digit:]]{2}){2}" | grep -A 30 "\\-------" | grep -A 29 "FAIL"; false)
                        # if grep -q gogenerate "Makefile";then  make gogenerate ; fi
                        """
                    }
                }
            }
        }

        def run_real_tikv_tests = { test_suite ->
            retry(5) {
                run_with_pod {
                    deleteDir()
                    unstash 'tidb'
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(45) {
                                if (ghprbTargetBranch == "master") {
                                    try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false\n" > tikv.toml
                                    echo -e "[raftdb]\nmax-open-files = 20480\n" >> tikv.toml
                                    echo -e "[rocksdb]\nmax-open-files = 20480\n" >> tikv.toml

                                    bin/pd-server --name=pd-0 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-0/data --peer-urls=http://127.0.0.1:2380 --advertise-peer-urls=http://127.0.0.1:2380 --client-urls=http://127.0.0.1:2379 --advertise-client-urls=http://127.0.0.1:2379  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd1.log &
                                    bin/pd-server --name=pd-1 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-1/data --peer-urls=http://127.0.0.1:2381 --advertise-peer-urls=http://127.0.0.1:2381 --client-urls=http://127.0.0.1:2382 --advertise-client-urls=http://127.0.0.1:2382  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd2.log &
                                    bin/pd-server --name=pd-2 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-2/data --peer-urls=http://127.0.0.1:2383 --advertise-peer-urls=http://127.0.0.1:2383 --client-urls=http://127.0.0.1:2384 --advertise-client-urls=http://127.0.0.1:2384  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --addr=127.0.0.1:20160 --advertise-addr=127.0.0.1:20160 --status-addr=127.0.0.1:20180 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-0/data -f  tikv1.log &
                                    bin/tikv-server --addr=127.0.0.1:20161 --advertise-addr=127.0.0.1:20161 --status-addr=127.0.0.1:20181 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-1/data -f  tikv2.log &
                                    bin/tikv-server --addr=127.0.0.1:20162 --advertise-addr=127.0.0.1:20162 --status-addr=127.0.0.1:20182 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-2/data -f  tikv3.log &

                                    sleep 10
                                    make bazel_${test_suite}
                                    """
                                    } catch (Exception e){
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    throw e
                                    } finally {
                                    sh """
                                    set +e
                                    killall -9 -r -q tikv-server
                                    killall -9 -r -q pd-server
                                    set -e
                                    """
                                    }
                                } else {
                                    try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false\n" > tikv.toml
                                    echo -e "[raftdb]\nmax-open-files = 20480\n" >> tikv.toml
                                    echo -e "[rocksdb]\nmax-open-files = 20480\n" >> tikv.toml

                                    bin/pd-server --name=pd-0 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-0/data --peer-urls=http://127.0.0.1:2380 --advertise-peer-urls=http://127.0.0.1:2380 --client-urls=http://127.0.0.1:2379 --advertise-client-urls=http://127.0.0.1:2379  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd1.log &
                                    bin/pd-server --name=pd-1 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-1/data --peer-urls=http://127.0.0.1:2381 --advertise-peer-urls=http://127.0.0.1:2381 --client-urls=http://127.0.0.1:2382 --advertise-client-urls=http://127.0.0.1:2382  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd2.log &
                                    bin/pd-server --name=pd-2 --data-dir=/home/jenkins/.tiup/data/T9Z9nII/pd-2/data --peer-urls=http://127.0.0.1:2383 --advertise-peer-urls=http://127.0.0.1:2383 --client-urls=http://127.0.0.1:2384 --advertise-client-urls=http://127.0.0.1:2384  --initial-cluster=pd-0=http://127.0.0.1:2380,pd-1=http://127.0.0.1:2381,pd-2=http://127.0.0.1:2383 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --addr=127.0.0.1:20160 --advertise-addr=127.0.0.1:20160 --status-addr=127.0.0.1:20180 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-0/data -f  tikv1.log &
                                    bin/tikv-server --addr=127.0.0.1:20161 --advertise-addr=127.0.0.1:20161 --status-addr=127.0.0.1:20181 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-1/data -f  tikv2.log &
                                    bin/tikv-server --addr=127.0.0.1:20162 --advertise-addr=127.0.0.1:20162 --status-addr=127.0.0.1:20182 --pd=http://127.0.0.1:2379,http://127.0.0.1:2382,http://127.0.0.1:2384 --config=tikv.toml --data-dir=/home/jenkins/.tiup/data/T9Z9nII/tikv-2/data -f  tikv3.log &

                                    sleep 10
                                    export log_level=error
                                    make failpoint-enable
                                    go test ./tests/realtikvtest/${test_suite} -v -with-real-tikv -timeout 30m
                                    """
                                    } catch (Exception e){
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    throw e
                                    } finally {
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
            }
        }

        if (ghprbTargetBranch == "master"){
            tests["New Collation Enabled"] = {
                run_with_pod {
                    deleteDir()
                    unstash 'tidb'
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                             timeout(30) {
                                 try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false" > tikv.toml

                                    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://127.0.0.1:2379 --peer-urls=http://127.0.0.1:2378 -force-new-cluster &> pd1.log &
                                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -C tikv.toml -f  tikv1.log &

                                    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://127.0.0.1:2389 --peer-urls=http://127.0.0.1:2388 -force-new-cluster &>  pd2.log &
                                    bin/tikv-server --pd=127.0.0.1:2389 -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -C tikv.toml -f  tikv2.log &

                                    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://127.0.0.1:2399 --peer-urls=http://127.0.0.1:2398 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --pd=127.0.0.1:2399 -s tikv3 --addr=0.0.0.0:20190 --advertise-addr=127.0.0.1:20190 --advertise-status-addr=127.0.0.1:20185 -C tikv.toml -f  tikv3.log &

                                    # GO111MODULE=on go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                                    ls -alh ./bin/

                                    export TIDB_SERVER_PATH=${ws}/bin/explain_test_tidb-server
                                    export TIKV_PATH=127.0.0.1:2379
                                    export TIDB_TEST_STORE_NAME="tikv"
                                    chmod +x tests/integrationtest/run-tests.sh
                                    cd tests/integrationtest && ls -alh
                                    ./run-tests.sh -d y
                                    """
                                 } catch (Exception e){
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    sh "cat ${ws}/tests/integrationtest/integration-test.out || true"
                                    throw e
                                 } finally {
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

            tests["New Collation Disabled"] = {
                run_with_pod {
                    deleteDir()
                    unstash 'tidb'
                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                             timeout(30) {
                                 try {
                                    ws = pwd()
                                    def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                                    def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                                    tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                                    def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                                    pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
                                    sh """
                                    curl ${tikv_url} | tar xz
                                    curl ${pd_url} | tar xz bin

                                    # Disable pipelined pessimistic lock temporarily until tikv#11649 is resolved
                                    echo -e "[pessimistic-txn]\npipelined = false" > tikv.toml

                                    bin/pd-server -name=pd1 --data-dir=pd1 --client-urls=http://127.0.0.1:2379 --peer-urls=http://127.0.0.1:2378 -force-new-cluster &> pd1.log &
                                    bin/tikv-server --pd=127.0.0.1:2379 -s tikv1 --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 --advertise-status-addr=127.0.0.1:20165 -C tikv.toml -f  tikv1.log &

                                    bin/pd-server -name=pd2 --data-dir=pd2 --client-urls=http://127.0.0.1:2389 --peer-urls=http://127.0.0.1:2388 -force-new-cluster &>  pd2.log &
                                    bin/tikv-server --pd=127.0.0.1:2389 -s tikv2 --addr=0.0.0.0:20170 --advertise-addr=127.0.0.1:20170 --advertise-status-addr=127.0.0.1:20175 -C tikv.toml -f  tikv2.log &

                                    bin/pd-server -name=pd3 --data-dir=pd3 --client-urls=http://127.0.0.1:2399 --peer-urls=http://127.0.0.1:2398 -force-new-cluster &> pd3.log &
                                    bin/tikv-server --pd=127.0.0.1:2399 -s tikv3 --addr=0.0.0.0:20190 --advertise-addr=127.0.0.1:20190 --advertise-status-addr=127.0.0.1:20185 -C tikv.toml -f  tikv3.log &

                                    # GO111MODULE=on go build -o bin/explain_test_tidb-server github.com/pingcap/tidb/tidb-server
                                    ls -alh ./bin/

                                    export TIDB_SERVER_PATH=${ws}/bin/explain_test_tidb-server
                                    export TIKV_PATH=127.0.0.1:2379
                                    export TIDB_TEST_STORE_NAME="tikv"
                                    chmod +x tests/integrationtest/run-tests.sh
                                    cd tests/integrationtest && ls -alh
                                    ./run-tests.sh -d n
                                    """
                                 } catch (Exception e){
                                    sh "cat ${ws}/pd1.log || true"
                                    sh "cat ${ws}/tikv1.log || true"
                                    sh "cat ${ws}/pd2.log || true"
                                    sh "cat ${ws}/tikv2.log || true"
                                    sh "cat ${ws}/pd3.log || true"
                                    sh "cat ${ws}/tikv3.log || true"
                                    sh "cat ${ws}/tests/integrationtest/integration-test.out || true"
                                    throw e
                                 } finally {
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

            tests["Real TiKV Tests - brietest"] = {
                run_real_tikv_tests("brietest")
            }
            tests["Real TiKV Tests - pessimistictest"] = {
                run_real_tikv_tests("pessimistictest")
            }
            tests["Real TiKV Tests - sessiontest"] = {
                run_real_tikv_tests("sessiontest")
            }
            tests["Real TiKV Tests - statisticstest"] = {
                run_real_tikv_tests("statisticstest")
            }
            tests["Real TiKV Tests - txntest"] = {
                run_real_tikv_tests("txntest")
            }
            if (ghprbTargetBranch == "master" || ghprbTargetBranch.matches("^feature[/_].*")) {
                tests["Real TiKV Tests - addindextest"] = {
                    run_real_tikv_tests("addindextest")
                }
            }
        }
        parallel tests

        currentBuild.result = "SUCCESS"

        container("golang"){
            sh """
                echo "done" > done
                curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
            """
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
} finally {
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
                [$class: 'StringParameterValue', name: 'PIPLINE_RUN_ERROR_CODE', value: "${ciErrorCode}"],
    ]
}

if (params.containsKey("triggered_by_upstream_ci")) {
    stage("update commit status") {
        node("master") {
            if (currentBuild.result == "ABORTED") {
                PARAM_DESCRIPTION = 'Jenkins job aborted'
                // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                PARAM_STATUS = 'error'
            } else if (currentBuild.result == "FAILURE") {
                PARAM_DESCRIPTION = 'Jenkins job failed'
                PARAM_STATUS = 'failure'
            } else if (currentBuild.result == "SUCCESS") {
                PARAM_DESCRIPTION = 'Jenkins job success'
                PARAM_STATUS = 'success'
            } else {
                PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                PARAM_STATUS = 'error'
            }
            def default_params = [
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/check_dev_2'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
