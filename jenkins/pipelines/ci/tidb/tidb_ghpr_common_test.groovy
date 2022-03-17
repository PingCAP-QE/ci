def notRun = 1

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

def TIDB_TEST_BRANCH = ghprbTargetBranch

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

// @NonCPS
// boolean isMoreRecentOrEqual( String a, String b ) {
//     if (a == b) {
//         return true
//     }

//     [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
//        Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
//        return (result == 1)
//     } 
// }

// string trimPrefix = {
//         it.startsWith('release-') ? it.minus('release-').split("-")[0] : it 
//     }

// def boolean isBranchMatched(List<String> branches, String targetBranch) {
//     for (String item : branches) {
//         if (targetBranch.startsWith(item)) {
//             println "targetBranch=${targetBranch} matched in ${branches}"
//             return true
//         }
//     }
//     return false
// }

// isNeedGo1160 = false
// releaseBranchUseGo1160 = "release-5.1"

// if (!isNeedGo1160) {
//     isNeedGo1160 = isBranchMatched(["master", "hz-poc", "ft-data-inconsistency", "br-stream"], ghprbTargetBranch)
// }
// if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
//     isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
//     if (isNeedGo1160) {
//         println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
//     }
// }
// if (isNeedGo1160) {
//     println "This build use go1.16"
//     POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.16:latest"
// } else {
//     println "This build use go1.13"
//     POD_GO_DOCKER_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.13:latest"
// }

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "tidb-ghpr-common-test-go1130-${BUILD_NUMBER}",
    "go1.16": "tidb-ghpr-common-test-go1160-${BUILD_NUMBER}",
    "go1.18": "tidb-ghpr-common-test-go1180-${BUILD_NUMBER}",
]
POD_NAMESPACE = "jenkins-tidb"

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

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def TIDB_TEST_STASH_FILE = "tidb_test_${UUID.randomUUID().toString()}.tar"

def run_test_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes"
    podTemplate(label: label,
            cloud: cloud,
            namespace: "jenkins-tidb",
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: POD_GO_IMAGE, ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],  
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
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

def run_test_with_java_pod(Closure body) {
    def label = "tidb-ghpr-common-test-java-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'java', alwaysPullImage: false,
                            image: "hub.pingcap.net/jenkins/centos7_golang-1.13_java:cached", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                    )
            ],
            volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: false),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}


all_task_result = []

try {
    timestamps {
        stage("Pre-check"){
            if (!params.force){
                node("lightweight_pod"){
                    container("golang"){
                        notRun = sh(returnStatus: true, script: """
				    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
				    """)
                    }
                }
            }

            if (notRun == 0){
                println "the ${ghprbActualCommit} has been tested"
                throw new RuntimeException("hasBeenTested")
            }
        }

        stage('Prepare') {

            run_test_with_pod {
                def ws = pwd()
                deleteDir()
                println "work space path:\n${ws}"

                container("golang") {
                    dir("go/src/github.com/pingcap/tidb") {
                        timeout(10) {
                            retry(3){
                                deleteDir()
                                sh """
	                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
	                        curl ${tidb_url} | tar xz
	                        """
                            }
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test") {
                        timeout(10) {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 5; done
                        """
                            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                        curl ${tidb_test_url} | tar xz
                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                        # export GOPROXY=http://goproxy.pingcap.net
                        cd tidb_test && ./build.sh && cd ..
                        cd mysql_test && ./build.sh && cd ..
                        cd randgen-test && ./build.sh && cd ..
                        cd analyze_test && ./build.sh && cd ..
                        if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                            cd randgen-test && ls t > packages.list
                            split packages.list -n r/3 packages_ -a 1 --numeric-suffixes=1
                            cd ..
                        fi
                        """

                            sh """
                            echo "stash tidb-test"
                            cd .. && tar -cf $TIDB_TEST_STASH_FILE tidb-test/
                            curl -F builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE}=@${TIDB_TEST_STASH_FILE} ${FILE_SERVER_URL}/upload
                        """
                        }
                    }
                }
                deleteDir()
            }
        }

        stage('Common Test') {
            def tests = [:]

            def run_with_log = { test_dir, log_path ->
                run_test_with_pod {
                    def ws = pwd()
                    deleteDir()
                    println "work space path:\n${ws}"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }
                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat ${log_path}"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                }
            }

            def run = { test_dir ->
                if (test_dir == "mysql_test"){
                    run_with_log("mysql_test", "mysql-test.out*")
                } else{
                    run_with_log(test_dir, "tidb*.log")
                }
            }

            def run_split = { test_dir, chunk ->
                run_test_with_pod {
                    def ws = pwd()
                    deleteDir()
                    println "work space path:\n${ws}"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh
                                if [ \"${ghprbTargetBranch}\" != \"release-2.0\" ]; then
                                    mv t t_bak
                                    mkdir t
                                    cd t_bak
                                    cp \$(cat ../packages_${chunk}) ../t
                                    cd ..
                                fi
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log*"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                }
            }

            def run_cache_log = { test_dir, log_path ->
                run_test_with_pod {
                    def ws = pwd()
                    deleteDir()
                    println "work space path:\n${ws}"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                CACHE_ENABLED=1 ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat ${log_path}"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    deleteDir()
                }
            }

            def run_cache = { test_dir ->
                run_cache_log(test_dir, "tidb*.log*")
            }

            def run_vendor = { test_dir ->
                run_test_with_pod {
                    def ws = pwd()
                    deleteDir()
                    println "work space path:\n${ws}"

                    container("golang") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                                sh """
                            if [ -f go.mod ]; then
                                GO111MODULE=on go mod vendor -v
                            fi
                            """
                            }
                        }

                        dir("go/src/github.com/pingcap/tidb_gopath") {
                            sh """
                        mkdir -p ./src
                        cp -rf ../tidb/vendor/* ./src
                        mv ../tidb/vendor ../tidb/_vendor
                        """
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=${ws}/go/src/github.com/pingcap/tidb-test/_vendor:${ws}/go/src/github.com/pingcap/tidb_gopath:${ws}/go \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                    deleteDir()
                }
            }

            def run_jdbc = { test_dir, testsh ->
                run_test_with_java_pod {
                    def ws = pwd()
                    deleteDir()
                    println "work space path:\n${ws}"

                    container("java") {
                        dir("go/src/github.com/pingcap/tidb") {
                            timeout(10) {
                                retry(3){
                                    sh """
		                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
		                        curl ${tidb_url} | tar xz
		                        """
                                }
                            }
                        }

                        dir("go/src/github.com/pingcap") {
                            sh """
                            echo "unstash tidb"
                            curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                        """
                        }

                        dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                            try {
                                timeout(10) {
                                    sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                mkdir -p ~/.m2 && cat <<EOF > ~/.m2/settings.xml
<settings>
  <mirrors>
    <mirror>
      <id>alimvn-central</id>
      <name>aliyun maven mirror</name>
      <url>https://maven.aliyun.com/repository/central</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF
                                
                                cat ~/.m2/settings.xml || true

                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                GOPATH=disable GOROOT=disable ${testsh}
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                }
                            } catch (err) {
                                sh "cat tidb*.log"
                                sh "cat *tidb.log"
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                                throw err
                            }
                        }
                    }
                }
            }

            tests["TiDB Test"] = {
                try {
                    run("tidb_test")
                    all_task_result << ["name": "TiDB Test", "status": "success", "error": ""]
                } catch (err) {
                    println "TiDB Test failed"
                    all_task_result << ["name": "TiDB Test", "status": "failed", "error": err.message]
                    throw err
                } 
            }


            tests["Randgen Test 1"] = {
                try {
                    run_split("randgen-test",1)
                    all_task_result << ["name": "Randgen Test 1", "status": "success", "error": ""]
                } catch (err) {
                    println "Randgen Test 1 failed"
                    all_task_result << ["name": "Randgen Test 1", "status": "failed", "error": err.message]
                    throw err
                } 
            }

            tests["Randgen Test 2"] = {
                try {
                    run_split("randgen-test",2)
                    all_task_result << ["name": "Randgen Test 2", "status": "success", "error": ""]
                } catch (err) {
                    println "Randgen Test 2 failed"
                    all_task_result << ["name": "Randgen Test 2", "status": "failed", "error": err.message]
                    throw err
                } 
            }

            tests["Randgen Test 3"] = {
                try {
                    run_split("randgen-test",3)
                    all_task_result << ["name": "Randgen Test 3", "status": "success", "error": ""]
                } catch (err) {
                    println "Randgen Test 3 failed"
                    all_task_result << ["name": "Randgen Test 3", "status": "failed", "error": err.message]
                    throw err
                } 
            }

            tests["Analyze Test"] = {
                try {
                    run("analyze_test")
                    all_task_result << ["name": "Analyze Test", "status": "success", "error": ""]
                } catch (err) {
                    println "Analyze Test failed"
                    all_task_result << ["name": "Analyze Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Mysql Test"] = {
                try {
                    run("mysql_test", "mysql-test.out*")
                    all_task_result << ["name": "Mysql Test", "status": "success", "error": ""]
                } catch (err) {
                    println "Mysql Test failed"
                    all_task_result << ["name": "Mysql Test", "status": "failed", "error": err.message]
                    throw err
                } 
            }

            if ( ghprbTargetBranch == "master" || ghprbTargetBranch.startsWith("release-3") ) {
                tests["Mysql Test Cache"] = {
                    try {
                        run_cache_log("mysql_test", "mysql-test.out*")
                        all_task_result << ["name": "Mysql Test Cache", "status": "success", "error": ""]
                    } catch (err) {
                        println "Mysql Test Cache failed"
                        all_task_result << ["name": "Mysql Test Cache", "status": "failed", "error": err.message]
                        throw err
                    }
                }
            }

            tests["JDBC Fast"] = {
                try {
                    run_jdbc("jdbc_test", "./test_fast.sh")
                    all_task_result << ["name": "JDBC Fast", "status": "success", "error": ""]
                } catch (err) {
                    println "JDBC Fast failed"
                    all_task_result << ["name": "JDBC Fast", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["JDBC Slow"] = {
                try {
                    run_jdbc("jdbc_test", "./test_slow.sh")
                    all_task_result << ["name": "JDBC Slow", "status": "success", "error": ""]
                } catch (err) {
                    println "JDBC Slow failed"
                    all_task_result << ["name": "JDBC Slow", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Gorm Test"] = {
                try {
                    run("gorm_test")
                    all_task_result << ["name": "Gorm Test", "status": "success", "error": ""]
                } catch (err) {
                    println "Gorm Test failed"
                    all_task_result << ["name": "Gorm Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["Go SQL Test"] = {
                try {
                    run("go-sql-test")
                    all_task_result << ["name": "Go SQL Test", "status": "success", "error": ""]
                } catch (err) {
                    println "Go SQL Test failed"
                    all_task_result << ["name": "Go SQL Test", "status": "failed", "error": err.message]
                    throw err
                }
            }

            tests["DDL ETCD Test"] = {
                try {
                    run_vendor("ddl_etcd_test")
                    all_task_result << ["name": "DDL ETCD Test", "status": "success", "error": ""]
                } catch (err) {
                    println "DDL ETCD Test failed"
                    all_task_result << ["name": "DDL ETCD Test", "status": "failed", "error": err.message]
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
                    curl -F ci_check/tidb_ghpr_common_test/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
                """
            }
        }
    }
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
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
    stage("task summary") {
        def json = groovy.json.JsonOutput.toJson(all_task_result)
        println "all_results: ${json}"
        currentBuild.description = "${json}"
    }
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
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}