echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    echo "release test: ${params.containsKey("release_test")}"
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tikv_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIDB_BRANCH = ghprbTargetBranch
def PD_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

if (ghprbPullTitle.find("Bump version") != null) {
    currentBuild.result = 'SUCCESS'
    return
}

// parse tikv branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    PD_BRANCH = "${m2[0][1]}"
}
if (PD_BRANCH == "4.0-perf") {
    PD_BRANCH = "master"
}
m2 = null
println "PD_BRANCH=${PD_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
// if (TIDB_TEST_BRANCH == "release-3.0"|| TIDB_TEST_BRANCH == "release-3.1") {
    // TIDB_TEST_BRANCH = "release-3.0"
// }

if (TIDB_TEST_BRANCH == "4.0-perf") {
    TIDB_TEST_BRANCH = "master"
}

m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

def specStr = "+refs/heads/*:refs/remotes/origin/*"
if (ghprbPullId != null && ghprbPullId != "") {
    specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
}

def tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/tikv-server.tar.gz"
def release="release"

def push_down_func_test_exist = false

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-') : it 
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}
if (isNeedGo1160) {
    println "This build use go1.16 because ghprTargetBranch=master"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = "test_heavy_go1160_memvolume"
} else {
    println "This build use go1.13"
    GO_TEST_SLAVE = "test_tikv_go1130_memvolume"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Prepare') {
        def prepares = [:]

        prepares["Part #1"] = {
            node("build") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                dir("tikv") {
                    container("rust") {
                        deleteDir()
                        timeout(30) {
                            sh """
                        set +e
                        while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                        set -e
                        (curl ${tikv_url} | tar xz) || (sleep 15 && curl ${tikv_url} | tar xz)
                        """
                        }
                    }
                }

                stash includes: "tikv/bin/**", name: "tikv"
                deleteDir()
            }
        }

        prepares["Part #2"] = {
            node("${GO_BUILD_SLAVE}") {
                def ws = pwd()
                deleteDir()


                container("golang") {

                    
                    dir("go/src/github.com/pingcap/tidb") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 
                        println "work space path:\n${ws}"
                        def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                        def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                        timeout(30) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                            curl ${tidb_url} | tar xz
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test") {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        timeout(30) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                            curl ${tidb_test_url} | tar xz

                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd tidb_test && GOPATH=${ws}/go ./build.sh && cd ..
                            cd randgen-test && GOPATH=${ws}/go ./build.sh && cd ..
                            """
                        }
                    }
                }

                stash includes: "go/src/github.com/pingcap/tidb-test/_helper.sh", name: "helper"
                stash includes: "go/src/github.com/pingcap/tidb-test/tidb_test/**", name: "tidb_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/randgen-test/**", name: "randgen-test"
                stash includes: "go/src/github.com/pingcap/tidb-test/go-sql-test/**", name: "go-sql-test"

                stash includes: "go/src/github.com/pingcap/tidb-test/go.*,go/src/github.com/pingcap/tidb-test/util/**,go/src/github.com/pingcap/tidb-test/bin/**", name: "tidb-test"
                deleteDir()
            }
        }

        prepares["Part #3"] = {
            node("${GO_BUILD_SLAVE}") {
                def ws = pwd()
                deleteDir()



                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 
                    println "work space path:\n${ws}"
                    dir("go/src/github.com/pingcap/tidb") {
                        def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                        def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                        timeout(30) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                            curl ${tidb_url} | tar xz
                            """
                        }
                    }

                    dir("go/src/github.com/pingcap/tidb-test") {
                        container("golang") {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                            timeout(30) {
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                                curl ${tidb_test_url} | tar xz

                                mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                cd mysql_test && GOPATH=${ws}/go ./build.sh && cd ..
                                cd analyze_test && GOPATH=${ws}/go ./build.sh && cd ..
                                """
                            }
                        }
                    }
                }

                stash includes: "go/src/github.com/pingcap/tidb-test/_vendor/**", name: "tidb-test-vendor"
                stash includes: "go/src/github.com/pingcap/tidb-test/mysql_test/**", name: "mysql_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/analyze_test/**", name: "analyze_test"
                stash includes: "go/src/github.com/pingcap/tidb-test/gorm_test/**", name: "gorm_test"
                if (fileExists('go/src/github.com/pingcap/tidb-test/push_down_func_test')) {
                    push_down_func_test_exist = false
                    stash includes: "go/src/github.com/pingcap/tidb-test/push_down_func_test/**", name: "push_down_func_test"
                }
                deleteDir()
            }
        }

        parallel prepares
    }

    stage('Integration Common Test') {
        def tests = [:]

        def run = { test_dir, mytest, test_cmd ->
            node("${GO_TEST_SLAVE}") {
                def ws = pwd()
                deleteDir()


                unstash "tidb-test"
                unstash "tidb-test-vendor"
                unstash "helper"
                unstash "${test_dir}"
                unstash "tikv"

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    container("golang") {

                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 
                        println "work space path:\n${ws}"
                        def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                        def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"


                        def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                        def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                        pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                        timeout(30) {
                            sh """
                            cp -r ${ws}/tikv/bin ./

                            while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                            curl ${pd_url} | tar xz bin

                            mkdir -p ./tidb-src
                            while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                            curl ${tidb_url} | tar xz -C ./tidb-src
                            ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"

                            mv tidb-src/bin/tidb-server ./bin/tidb-server
                            """
                        }

                        try {
                            timeout(15) {
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

                                mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                export log_level=debug
                                TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                TIKV_PATH='127.0.0.1:2379' \
                                TIDB_TEST_STORE_NAME=tikv \
                                GOPATH=${ws}/go \
                                ${test_cmd}
                                """
                            }
                        } catch (err) {
                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
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

        tests["Integration Randgen Test"] = {
            run("randgen-test", "randgentest", "./test.sh")
        }

        tests["Integration Analyze Test"] = {
            run("analyze_test", "analyzetest", "./test.sh")
        }

        tests["Integration TiDB Test 1"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_1.t ./test.sh")
        }

        tests["Integration TiDB Test 2"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_2.t ./test.sh")
        }

        tests["Integration Go SQL Test"] = {
            run("go-sql-test", "gosqltest", "./test.sh")
        }

        tests["Integration GORM Test"] = {
            run("gorm_test", "gormtest", "./test.sh")
        }

        tests["Integration MySQL Test"] = {
            run("mysql_test", "mysqltest", "./test.sh")
        }

        tests["Integration MySQL Test Cached"] = {
            run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh")
        }

        if (ghprbTargetBranch == "master" && push_down_func_test_exist) {
            tests["Integration cop Test"] = {
                def tidb_master_sha1, pd_master_sha1
                
                node('delivery') {
                    container('delivery') {
                        dir ('centos7') {
                            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                            
                            tidb_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=master -s=${FILE_SERVER_URL}").trim()
                            pd_sha1 = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=master -s=${FILE_SERVER_URL}").trim()
                        }
                    }
                }

                node("${GO_TEST_SLAVE}") {
                    def ws = pwd()
                    deleteDir()

                    unstash "tidb-test"
                    unstash "tidb-test-vendor"
                    unstash "helper"
                    unstash "push_down_func_test"
                    unstash "tikv"

                    dir("go/src/github.com/pingcap/tidb-test/push_down_func_test") {
                        container("golang") {
                            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 
                            println "work space path:\n${ws}"
                            
                            def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"
                            def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                            def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"


                            def pd_refs = "${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
                            def pd_sha1 = sh(returnStdout: true, script: "curl ${pd_refs}").trim()
                            pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"

                            timeout(30) {
                                sh """
                                cp -r ${ws}/tikv/bin ./
                                curl ${pd_url} | tar xz

                                mkdir -p ./tidb-src
                                curl ${tidb_url} | tar xz -C ./tidb-src

                                ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"

                                mv tidb-src/bin/tidb-server ./bin/tidb-server
                                """
                            }

                            try {
                                timeout(10) {
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

                                    mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                    export log_level=debug
                                    TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                    tidb_master_bin=`pwd`/bin/tidb-server \
                                    tidb_master_sha=$tidb_sha1 \
                                    pd_master_bin=`pwd`/bin/pd-server \
                                    pd_master_sha=$pd_sha1\
                                    tikv_cur_bin=`pwd`/bin/tikv-server \
                                    tikv_cur_sha=$ghprbActualCommit
                                    TIKV_PATH='127.0.0.1:2379' \
                                    TIDB_TEST_STORE_NAME=tikv \
                                    GOPATH=${ws}/go \
                                    ${test_cmd}
                                    """
                                }
                            } catch (err) {
                                sh """
                                cat pd_${mytest}.log
                                cat tikv_${mytest}.log
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
        }

        parallel tests
    }

    node("master") {
        stage("trigger the dist release job if necessary") {
            if ( !params.containsKey("triggered_by_upstream_ci")) {
                def tikv_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/pr/${ghprbActualCommit}/centos7/done"
                if (sh(returnStatus: true, script: "curl --output /dev/null --silent --head --fail ${tikv_done_url}") != 0) {
                    build job: 'tikv_ghpr_build_release', wait: false, parameters: [
                            [$class: 'StringParameterValue', name: 'ghprbActualCommit', value: ghprbActualCommit],
                            [$class: 'StringParameterValue', name: 'ghprbPullId', value: ghprbPullId],
                            [$class: 'StringParameterValue', name: 'ghprbTargetBranch', value: ghprbTargetBranch],
                            [$class: 'StringParameterValue', name: 'ghprbPullTitle', value: ghprbPullTitle],
                            [$class: 'StringParameterValue', name: 'ghprbPullLink', value: ghprbPullLink],
                            [$class: 'StringParameterValue', name: 'ghprbPullDescription', value: ghprbPullDescription],
                            [$class: 'BooleanParameterValue', name: 'notcomment', value: true]]
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
}
catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    currentBuild.result = "ABORTED"
} 
catch(Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
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
                    string(name: 'TIKV_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tikv/integration-common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tikv_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
