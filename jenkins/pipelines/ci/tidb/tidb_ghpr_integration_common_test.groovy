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

def TIKV_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

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

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH.startsWith("release-3")) {
// TIDB_TEST_BRANCH = "release-3.0"
// }
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def testStartTimeMillis = System.currentTimeMillis()

POD_CLOUD = "kubernetes-ksyun"
POD_NAMESPACE = "jenkins-tidb"
GOPROXY="http://goproxy.apps.svc,https://proxy.golang.org,direct"

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

podYAML = '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    ci-engine: ci.pingcap.net
'''

def run_with_pod(Closure body) {
    def label = "tidb-ghpr-integration-common-test-${BUILD_NUMBER}"
    podTemplate(label: label,
            cloud: POD_CLOUD,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/go', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

def run_with_memory_volume_pod(Closure body) {
    def label = "tidb-ghpr-integration-common-test-memory-volume-${BUILD_NUMBER}"
    podTemplate(label: label,
            cloud: POD_CLOUD,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/go', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            println "this pod use memory volume"
            body()
        }
    }
}
def parallel_run_mysql_test(branch) {
    if (branch in ["master"]) {
        return true
    }
    if (branch.startsWith("release-") && branch >= "release-6.2") {
        return true
    }
    return false
}


all_task_result = []

try {
    timestamps {
        stage('Prepare') {
            def prepareStartTime = System.currentTimeMillis()

            def prepares = [:]

            prepares["Part #1"] = {
                run_with_pod {
                    def ws = pwd()
                    deleteDir()

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(20) {
                                retry(3){
                                    deleteDir()
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                                    tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                                    """
                                }
                            }
                        }

                        dir("go/src/github.com/PingCAP-QE/tidb-test") {
                            timeout(20) {
                                def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                                """
                                def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                                def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                                wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O tidb-test.tar.gz ${tidb_test_url}
                                tar -xz -f tidb-test.tar.gz && rm -rf tidb-test.tar.gz

                                unset GOPROXY && go env -w GOPROXY=${GOPROXY} && go env
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                cd tidb_test && ./build.sh && cd ..
                                if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                    cd randgen-test && ./build.sh && cd ..
                                    cd randgen-test && ls t > packages.list
                                    split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                                    cd ..
                                fi
                                """
                            }
                        }
                    }

                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/_helper.sh", name: "helper"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/tidb_test/**", name: "tidb_test"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/randgen-test/**", name: "randgen-test"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/go-sql-test/**", name: "go-sql-test"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/go.*,go/src/github.com/PingCAP-QE/tidb-test/util/**,go/src/github.com/PingCAP-QE/tidb-test/bin/**", name: "tidb-test"
                    deleteDir()
                }
            }

            prepares["Part #2"] = {
                run_with_pod {
                    def ws = pwd()
                    deleteDir()

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(20) {
                                retry(3){
                                    deleteDir()
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                                    tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                                    """
                                }
                            }
                        }
                        dir("go/src/github.com/PingCAP-QE/tidb-test") {
                            container("golang") {
                                timeout(20) {
                                    def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 15; done
                                    """
                                    def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                                    def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                                    sh """
                                    echo ${tidb_test_url}
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O tidb-test.tar.gz ${tidb_test_url}
                                    tar -xz -f tidb-test.tar.gz && rm -rf tidb-test.tar.gz
                                    unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                    cd mysql_test && ./build.sh && cd ..
                                    cd analyze_test && ./build.sh && cd ..
                                    """
                                }
                            }
                        }
                    }
                    stash includes: "go/src/github.com/pingcap/tidb/bin/tidb-server", name: "tidb-server"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/_vendor/**", name: "tidb-test-vendor"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/mysql_test/**", name: "mysql_test"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/analyze_test/**", name: "analyze_test"
                    stash includes: "go/src/github.com/PingCAP-QE/tidb-test/gorm_test/**", name: "gorm_test"
                    deleteDir()
                }
            }

            parallel prepares
        }

        stage('Integration Common Test') {
            testStartTimeMillis = System.currentTimeMillis()
            def tests = [:]

            def run = { test_dir, mytest, test_cmd ->
                run_with_memory_volume_pod {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-server"
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"

                    dir("go/src/github.com/PingCAP-QE/tidb-test/${test_dir}") {
                        container("golang") {
                            timeout(20) {
                                retry(3){
                                    sh """
                                    tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"
                                    pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"

                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 1; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${tikv_url}
                                    tar -xvz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 1; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 \${pd_url}
                                    tar -xvz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz

                                    # mkdir -p ./tidb-src
                                    # while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                    # wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 ${tidb_url}
                                    # tar -xz -C ./tidb-src -f tidb-server.tar.gz
                                    # ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"

                                    mv ${WORKSPACE}/go/src/github.com/pingcap/tidb/bin/tidb-server ./bin/tidb-server
                                    """
                                }

                            }

                            try {
                                timeout(60) {
                                    sh """
                                    ps aux
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    rm -rf ./tikv ./pd
                                    set -e

                                    bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                    sleep 10
                                    echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                    bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                    sleep 10
                                    if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi
                                    unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                                    export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                    export log_level=debug
                                    TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                    TIKV_PATH='127.0.0.1:2379' \
                                    TIDB_TEST_STORE_NAME=tikv \
                                    ${test_cmd}
                                    """
                                }
                            } catch (err) {
                                sh"""
                                cat mysql-test.out || true
                                """
                                sh """
                                cat pd_${mytest}.log
                                cat tikv_${mytest}.log
                                cat tidb*.log
                                """
                                throw err
                            } finally {
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

            def run_split = { test_dir, mytest, test_cmd, chunk ->
                run_with_memory_volume_pod {
                    def ws = pwd()
                    deleteDir()
                    unstash "tidb-server"
                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "${test_dir}"

                    dir("go/src/github.com/PingCAP-QE/tidb-test/${test_dir}") {
                        container("golang") {

                            timeout(20) {
                                retry(3){
                                    sh """
                                    tikv_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"`
                                    tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/\${tikv_sha1}/centos7/tikv-server.tar.gz"

                                    pd_sha1=`curl "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"`
                                    pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/\${pd_sha1}/centos7/pd-server.tar.gz"

                                    while ! curl --output /dev/null --silent --head --fail \${tikv_url}; do sleep 15; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${tikv_url}
                                    tar -xvz bin/ -f tikv-server.tar.gz && rm -rf tikv-server.tar.gz

                                    while ! curl --output /dev/null --silent --head --fail \${pd_url}; do sleep 15; done
                                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  \${pd_url}
                                    tar -xvz bin/ -f pd-server.tar.gz && rm -rf pd-server.tar.gz

                                    # mkdir -p ./tidb-src
                                    # while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 15; done
                                    # wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 ${tidb_url}
                                    # tar -xz -C ./tidb-src -f tidb-server.tar.gz
                                    # ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"
                                    # mv tidb-src/bin/tidb-server ./bin/tidb-server

                                    mv ${WORKSPACE}/go/src/github.com/pingcap/tidb/bin/tidb-server ./bin/tidb-server
                                    """
                                }
                            }

                            try {
                                timeout(20) {
                                    sh """
                                    set +e
                                    killall -9 -r tidb-server
                                    killall -9 -r tikv-server
                                    killall -9 -r pd-server
                                    rm -rf /tmp/tidb
                                    rm -rf ./tikv ./pd
                                    set -e

                                    bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                    sleep 10
                                    echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml

                                    bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                    sleep 10
                                    unset GOPROXY && go env -w GOPROXY=${GOPROXY}
                                    if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi
                                    if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                        mv t t_bak
                                        mkdir t
                                        cd t_bak
                                        cp \$(cat ../packages_${chunk}) ../t
                                        cd ..
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export log_level=debug
                                        TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                        TIKV_PATH='127.0.0.1:2379' \
                                        TIDB_TEST_STORE_NAME=tikv \
                                        ${test_cmd}
                                    fi
                                    """
                                }
                            } catch (err) {
                                sh"""
                                cat mysql-test.out || true
                                """

                                sh """
                                cat pd_${mytest}.log
                                cat tikv_${mytest}.log
                                cat tidb*.log
                                """
                                throw err
                            } finally {
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
                    deleteDir()
                }
            }

            tests["Integration Randgen Test 1"] = {
                try {
                    run_split("randgen-test", "randgentest", "./test.sh", 1)
                    all_task_result << ["name": "Randgen Test 1", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Randgen Test 1", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration Randgen Test 2"] = {
                try {
                    run_split("randgen-test", "randgentest", "./test.sh", 2)
                    all_task_result << ["name": "Randgen Test 2", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Randgen Test 2", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration Randgen Test 3"] = {
                try {
                    run_split("randgen-test", "randgentest", "./test.sh", 3)
                    all_task_result << ["name": "Randgen Test 3", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Randgen Test 3", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration Analyze Test"] = {
                try {
                    run("analyze_test", "analyzetest", "./test.sh")
                    all_task_result << ["name": "Analyze Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Analyze Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration TiDB Test 1"] = {
                try {
                    run("tidb_test", "tidbtest", "TEST_FILE=ql_1.t ./test.sh")
                    all_task_result << ["name": "TiDB Test 1", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "TiDB Test 1", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration TiDB Test 2"] = {
                try {
                    run("tidb_test", "tidbtest", "TEST_FILE=ql_2.t ./test.sh")
                    all_task_result << ["name": "TiDB Test 2", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "TiDB Test 2", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration Go SQL Test"] = {
                try {
                    run("go-sql-test", "gosqltest", "./test.sh")
                    all_task_result << ["name": "Go SQL Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Go SQL Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Integration GORM Test"] = {
                try {
                    run("gorm_test", "gormtest", "./test.sh")
                    all_task_result << ["name": "GORM Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "GORM Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            if (parallel_run_mysql_test(ghprbTargetBranch)) {
                tests["Integration MySQL Test1"] = {
                    try {
                        run("mysql_test", "mysqltest", "./test.sh -backlist=1 -part=1")
                        all_task_result << ["name": "MySQL Test1", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test1", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test2"] = {
                    try {
                        run("mysql_test", "mysqltest", "./test.sh -backlist=1 -part=2")
                        all_task_result << ["name": "MySQL Test2", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test2", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Tes3"] = {
                    try {
                        run("mysql_test", "mysqltest", "./test.sh -backlist=1 -part=3")
                        all_task_result << ["name": "MySQL Test3", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test3", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Tes4"] = {
                    try {
                        run("mysql_test", "mysqltest", "./test.sh -backlist=1 -part=4")
                        all_task_result << ["name": "MySQL Test4", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test4", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test Cached1"] = {
                    try {
                        run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh -backlist=1 -part=1")
                        all_task_result << ["name": "MySQL Test Cached1", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test Cached1", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test Cached2"] = {
                    try {
                        run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh -backlist=1 -part=2")
                        all_task_result << ["name": "MySQL Test Cached2", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test Cached2", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test Cached3"] = {
                    try {
                        run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh -backlist=1 -part=3")
                        all_task_result << ["name": "MySQL Test Cached3", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test Cached3", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test Cached4"] = {
                    try {
                        run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh -backlist=1 -part=4")
                        all_task_result << ["name": "MySQL Test Cached4", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test Cached4", "status": "failed", "error": err.message]
                        throw err
                    }
                }
            } else {
                tests["Integration MySQL Test"] = {
                    try {
                        run("mysql_test", "mysqltest", "./test.sh")
                        all_task_result << ["name": "MySQL Test", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test", "status": "failed", "error": err.message]
                        throw err
                    }
                }
                tests["Integration MySQL Test Cached"] = {
                    try {
                        run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh")
                        all_task_result << ["name": "MySQL Test Cached", "status": "success", "error": ""]
                    } catch (err) {
                        all_task_result << ["name": "MySQL Test Cached", "status": "failed", "error": err.message]
                        throw err
                    }
                }
            }

            tests["Integration Explain Test"] = {
                try {
                    run_with_memory_volume_pod {
                        def ws = pwd()
                        deleteDir()
                        dir("go/src/github.com/pingcap/tidb") {
                            container("golang") {
                                try {
                                    timeout(20) {
                                        retry(3){
                                            deleteDir()
                                            sh """
                                            while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                            wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0  ${tidb_url}
                                            tar -xz -f tidb-server.tar.gz && rm -rf tidb-server.tar.gz
                                            """
                                        }

                                    }

                                    timeout(20) {
                                        sh """
                                        if [ -d cmd/explaintest ]; then
                                            echo "explaintest file found in 'cmd/explaintest'"
                                            cp bin/tidb-server cmd/explaintest
                                            cp bin/importer cmd/explaintest
                                            cd cmd/explaintest
                                            GO111MODULE=on go build -o explain_test
                                            set +e
                                            killall -9 -r tidb-server
                                            killall -9 -r tikv-server
                                            killall -9 -r pd-server
                                            rm -rf /tmp/tidb
                                            set -e
                                            ./run-tests.sh -s ./tidb-server -i ./importer -b n
                                        elif [ -d tests/integrationtest ]; then
                                            echo "tests/integrationtest file found in 'tests/integrationtest'"
                                            cp bin/tidb-server tests/integrationtest
                                            cp bin/importer tests/integrationtest
                                            set +e
                                            killall -9 -r tidb-server
                                            killall -9 -r tikv-server
                                            killall -9 -r pd-server
                                            rm -rf /tmp/tidb
                                            set -e
                                            make integrationtest
                                        else
                                            echo "explaintest file not found"
                                            exit 1
                                        fi
                                        """
                                    }
                                } catch (err) {
                                    sh """
                                    cat tidb*.log || true
                                    """
                                    sh "cat explain-test.out || true"
                                    throw err
                                } finally {
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
                    all_task_result << ["name": "Explain Test", "status": "success", "error": ""]
                } catch (err) {
                    all_task_result << ["name": "Explain Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            parallel tests
        }

        currentBuild.result = "SUCCESS"
        node("lightweight_pod"){
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
    println "catch_exception FlowInterruptedException"
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println "catch_exception AbortException"
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println "catch_exception InterruptedException"
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    println "catch_exception Exception"
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}
finally {
    stage("task summary") {
        if (all_task_result) {
            def json = groovy.json.JsonOutput.toJson(all_task_result)
            println "all_results: ${json}"
            currentBuild.description = "${json}"
        }
    }
}

if (params.containsKey("triggered_by_upstream_ci")  && params.get("triggered_by_upstream_ci") == "tidb_integration_test_ci") {
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/integration-common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
