/*
    Run dm unit/intergation test in Jenkins with String paramaters

    * ghprbActualCommit (by bot)
    * ghprbPullId (by bot)

    * COVERALLS_TOKEN (set default in jenkins admin)
    * CODECOV_TOKEN (set default in jenkins admin)

*/

// def ghprbSourceBranch = "${ghprbSourceBranch}"
// if (ghprbSourceBranch.contains('cherry-pick') && "${ghprbPullAuthorLogin}"=='ti-chi-bot') {
//     echo 'skip this cherry pick test'
//     currentBuild.result = 'SUCCESS'
//     return 0
// }

// prepare all vars
if ("${ghprbTargetBranch}" == 'release-1.0') {
    MYSQL_ARGS = '--ssl=OFF --log-bin --binlog-format=ROW --enforce-gtid-consistency=ON --gtid-mode=ON --server-id=1 --default-authentication-plugin=mysql_native_password'
}else {
    MYSQL_ARGS = '--log-bin --binlog-format=ROW --enforce-gtid-consistency=ON --gtid-mode=ON --server-id=1 --default-authentication-plugin=mysql_native_password'
}

MYSQL_HOST = '127.0.0.1'
MYSQL_PORT = 3306
MYSQL2_PORT = 3307
MYSQL_PSWD = 123456

def print_all_vars() {
    println '================= ALL TEST VARS ================='
    println "[MYSQL_HOST]: ${MYSQL_HOST}"
    println "[MYSQL_PORT]: ${MYSQL_PORT}"
    println "[MYSQL2_PORT]: ${MYSQL2_PORT}"
    println "[MYSQL_PSWD]: ${MYSQL_PSWD}"
    println "[MYSQL_ARGS]: ${MYSQL_ARGS}"
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

def isNeedGo1160 = isBranchMatched(['master', "release-2.0"], ghprbTargetBranch)
if (isNeedGo1160) {
    println 'This build use go1.16'
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
    POD_GO_DOCKER_IMAGE = 'hub.pingcap.net/pingcap/centos7_golang-1.16:latest'
} else {
    println 'This build use go1.13'
    POD_GO_DOCKER_IMAGE = 'hub.pingcap.net/jenkins/centos7_golang-1.13:cached'
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"
println "POD_GO_DOCKER_IMAGE=${POD_GO_DOCKER_IMAGE}"

def checkout_and_stash_dm_code() {
    node("${GO_BUILD_SLAVE}") {
        container('golang') {
            deleteDir()

            dir('/home/jenkins/agent/git/dm') {
                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) { deleteDir() }
                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/dm.git']]]
            }

            dir('go/src/github.com/pingcap/dm') {
                sh """export GOPROXY=https://goproxy.cn
                    archive=dm-go-mod-cache_latest_\$(go version | awk '{ print \$3; }').tar.gz
                    archive_url=${FILE_SERVER_URL}/download/builds/pingcap/dm/cache/\$archive
                    if [ ! -f /tmp/\$archive ]; then
                        curl -sL \$archive_url -o /tmp/\$archive
                        tar --skip-old-files -xf /tmp/\$archive -C / || true
                    fi
                    cp -R /home/jenkins/agent/git/dm/. ./
                    git checkout -f ${ghprbActualCommit}
                    """
            }
            stash includes: 'go/src/github.com/pingcap/dm/**', name: 'dm', useDefaultExcludes: false
        }
    }
}

def run_make_check() {
    node("${GO_TEST_SLAVE}") {
        container('golang') {
            sh 'rm -rf /tmp/dm_test & mkdir -p /tmp/dm_test'
            def ws = pwd()
            deleteDir()
            unstash 'dm'
            dir('go/src/github.com/pingcap/dm') {
                container('golang') {
                    timeout(30) {
                        sh "GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check"
                    }
                }
            }
        }
    }
}

def build_dm_bin() {
    node("${GO_BUILD_SLAVE}") {
        container('golang') {
            deleteDir()
            unstash 'dm'
            ws = pwd()
            dir('go/src/github.com/pingcap/dm') {
                // build it test bin
                sh 'make dm_integration_test_build'

                // tidb
                tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/master/sha1").trim()
                sh "curl -o tidb-server.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
                sh 'mkdir -p tidb-server'
                sh 'tar -zxf tidb-server.tar.gz -C tidb-server'
                sh 'mv tidb-server/bin/tidb-server bin/'
                sh 'rm -r tidb-server'
                sh 'rm -r tidb-server.tar.gz'

                sh 'curl -L https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz | tar xz'
                sh 'mv tidb-enterprise-tools-nightly-linux-amd64/bin/sync_diff_inspector bin/'
                sh 'mv tidb-enterprise-tools-nightly-linux-amd64/bin/mydumper bin/'
                sh 'rm -r tidb-enterprise-tools-nightly-linux-amd64 || true'

                // use a new version of gh-ost to overwrite the one in container("golang") (1.0.47 --> 1.1.0)
                sh 'curl -L https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz | tar xz'
                sh 'mv gh-ost bin/'

                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} bash"
            }
            dir("${ws}") {
                stash includes: 'go/src/github.com/pingcap/dm/**', name: 'dm-with-bin', useDefaultExcludes: false
            }
        }
    }
}

def run_single_unit_test(String case_name) {
    def label = 'dm-unit-test'
    podTemplate(label: label,
            nodeSelector: 'role_type=slave',
            namespace: 'jenkins-tidb',
            idleMinutes: 10,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_DOCKER_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: 'cat'),
                    containerTemplate(
                            name: 'mysql', alwaysPullImage: false,
                            image: 'hub.pingcap.net/jenkins/mysql:5.7',ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
                            envVars: [
                                    envVar(key: 'MYSQL_ROOT_PASSWORD', value: "${MYSQL_PSWD}"),
                            ],
                            args: "${MYSQL_ARGS}")
            ]
    ) {
        node(label) {
            println "${NODE_NAME}"
            println "debug command:\nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} bash"
            container('golang') {
                ws = pwd()
                deleteDir()
                unstash 'dm-with-bin'
                dir('go/src/github.com/pingcap/dm') {
                    sh """
                                rm -rf /tmp/dm_test
                                mkdir -p /tmp/dm_test
                                export MYSQL_HOST=${MYSQL_HOST}
                                export MYSQL_PORT=${MYSQL_PORT}
                                export MYSQL_PSWD=${MYSQL_PSWD}
                                export GOPATH=\$GOPATH:${ws}/go
                                # wait for mysql
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done

                                make unit_test_${case_name}
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/dm_test
                                cp /tmp/dm_test/cov*out cov_dir
                                """
                }
                // stash this test coverage file
                stash includes: 'go/src/github.com/pingcap/dm/cov_dir/**', name: "unit-cov-${case_name}"
                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} bash"
            }
        }
    }
}

def run_single_it_test(String case_name) {
    def label = 'dm-integration-test'
    podTemplate(label: label,
            nodeSelector: 'role_type=slave',
            namespace: 'jenkins-tidb',
            idleMinutes: 30,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_DOCKER_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: 'cat'),
                    containerTemplate(
                            name: 'mysql1', alwaysPullImage: false,
                            image: 'hub.pingcap.net/jenkins/mysql:5.7',ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
                            envVars: [
                                    envVar(key: 'MYSQL_ROOT_PASSWORD', value: "${MYSQL_PSWD}"),
                            ],
                            args: "${MYSQL_ARGS}"),
                    // mysql 8.0
                    containerTemplate(
                            name: 'mysql2', alwaysPullImage: false,
                            image: 'hub.pingcap.net/zhangxuecheng/mysql:8.0.21',ttyEnabled: true,
                            resourceRequestCpu: '1000m', resourceRequestMemory: '1Gi',
                            envVars: [
                                    envVar(key: 'MYSQL_ROOT_PASSWORD', value: "${MYSQL_PSWD}"),
                                    envVar(key: 'MYSQL_TCP_PORT', value: "${MYSQL2_PORT}")
                            ],
                            args: "${MYSQL_ARGS}")
            ],
            volumes:[emptyDirVolume(mountPath: '/tmp', memory: true),
                     emptyDirVolume(mountPath: '/home/jenkins', memory: true)]) {
        node(label) {
            println "${NODE_NAME}"
            println "debug command: \nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} -c golang bash"
            container('golang') {
                def ws = pwd()
                deleteDir()
                unstash 'dm-with-bin'
                dir('go/src/github.com/pingcap/dm') {
                    try {
                        sh"""
                                # use a new version of gh-ost to overwrite the one in container("golang") (1.0.47 --> 1.1.0)
                                export PATH=bin:$PATH

                                rm -rf /tmp/dm_test
                                mkdir -p /tmp/dm_test

                                export MYSQL_HOST1=${MYSQL_HOST}
                                export MYSQL_PORT1=${MYSQL_PORT}
                                export MYSQL_HOST2=${MYSQL_HOST}
                                export MYSQL_PORT2=${MYSQL2_PORT}

                                # wait for mysql container ready.
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                # run test
                                export GOPATH=\$GOPATH:${ws}/go
                                make integration_test CASE="${case_name}"
                                # upload coverage
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/dm_test
                                cp /tmp/dm_test/cov*out cov_dir
                                """
                    }catch (Exception e) {
                        sh """
                                    echo "${case_name} test faild print all log..."
                                    for log in `ls /tmp/dm_test/*/*/log/*.log`; do
                                        echo "____________________________________"
                                        echo "\$log"
                                        cat "\$log"
                                        echo "____________________________________"
                                    done
                                    """
                        throw e
                    }
                }
                stash includes: 'go/src/github.com/pingcap/dm/cov_dir/**', name: "integration-cov-${case_name}"
            }
        }
    }
}

def run_make_coverage() {
    node("${GO_TEST_SLAVE}") {
        println "debug command:\nkubectl -n jenkins-tidb exec -ti ${env.NODE_NAME} bash"
        ws = pwd()
        deleteDir()
        unstash 'dm-with-bin'
        unstash 'unit-cov-relay'
        unstash 'unit-cov-syncer'
        unstash 'unit-cov-pkg_binlog'
        unstash 'unit-cov-others'
        try {
            unstash 'integration-cov-all_mode'
            unstash 'integration-cov-dmctl_advance dmctl_basic dmctl_command'
            unstash 'integration-cov-ha_cases'
            unstash 'integration-cov-ha_cases_1'
            unstash 'integration-cov-ha_cases_2'
            unstash 'integration-cov-ha_cases2'
            unstash 'integration-cov-ha_cases3'
            unstash 'integration-cov-ha_cases3_1'
            unstash 'integration-cov-ha_master'
            unstash 'integration-cov-handle_error'
            unstash 'integration-cov-handle_error_2'
            unstash 'integration-cov-handle_error_3'
            unstash 'integration-cov-import_goroutine_leak incremental_mode initial_unit'
            unstash 'integration-cov-load_interrupt'
            unstash 'integration-cov-many_tables'
            unstash 'integration-cov-online_ddl'
            unstash 'integration-cov-relay_interrupt'
            unstash 'integration-cov-safe_mode sequence_safe_mode'
            unstash 'integration-cov-shardddl1'
            unstash 'integration-cov-shardddl1_1'
            unstash 'integration-cov-shardddl2'
            unstash 'integration-cov-shardddl2_1'
            unstash 'integration-cov-shardddl3'
            unstash 'integration-cov-shardddl3_1'
            unstash 'integration-cov-shardddl4'
            unstash 'integration-cov-shardddl4_1'
            unstash 'integration-cov-sharding sequence_sharding'
            unstash 'integration-cov-start_task'
            unstash 'integration-cov-print_status http_apis'
            unstash 'integration-cov-new_relay'
            unstash 'integration-cov-import_v10x'
            unstash 'integration-cov-tls'
            unstash 'integration-cov-sharding2'
            unstash 'integration-cov-ha'
            unstash 'integration-cov-others'
        } catch (Exception e) {
            println e
        }
        dir('go/src/github.com/pingcap/dm') {
            container('golang') {
                timeout(30) {
                    sh """
                    rm -rf /tmp/dm_test
                    mkdir -p /tmp/dm_test
                    cp cov_dir/* /tmp/dm_test
                    set +x
                    BUILD_NUMBER=${BUILD_NUMBER} COVERALLS_TOKEN="${COVERALLS_TOKEN}" CODECOV_TOKEN="${CODECOV_TOKEN}" PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make coverage || true
                    set -x
                    """
                }
            }
        }
    }
}

pipeline {
    agent any

    stages {
        stage('Check Code') {
            steps {
                print_all_vars()
                script {
                    try {
                        checkout_and_stash_dm_code()
                    }catch (info) {
                        retry(count: 3) {
                            echo 'checkout failed, retry..'
                            sleep 1
                            checkout_and_stash_dm_code()
                        }
                    }
                }
            // run_make_check() check code style move to github actions..
            }
        }

        stage('Build Bin') {
            options { retry(count: 3) }
            steps {
                build_dm_bin()
            }
        }

        stage('Parallel Run Tests') {
            failFast true
            parallel {
                // Unit Test
                stage('UT-relay') {
                    steps {
                        script {
                            run_single_unit_test('relay')
                        }
                    }
                }

                stage('UT-syncer') {
                    steps {
                        script {
                            run_single_unit_test('syncer')
                        }
                    }
                }

                stage('UT-pkg_binlog') {
                    steps {
                        script {
                            run_single_unit_test('pkg_binlog')
                        }
                    }
                }

                stage('UT-others') {
                    steps {
                        script {
                            run_single_unit_test('others')
                        }
                    }
                }
                // END Unit Test

                // Integration Test
                stage('IT-all_mode') {
                    steps {
                        script {
                            run_single_it_test('all_mode')
                        }
                    }
                }

                stage('IT-dmctl') {
                    steps {
                        script {
                            run_single_it_test('dmctl_advance dmctl_basic dmctl_command')
                        }
                    }
                }

                stage('IT-ha_cases') {
                    steps {
                        script {
                            run_single_it_test('ha_cases')
                        }
                    }
                }

                stage('IT-ha_cases_1') {
                    steps {
                        script {
                            run_single_it_test('ha_cases_1')
                        }
                    }
                }

                stage('IT-ha_cases_2') {
                    steps {
                        script {
                            run_single_it_test('ha_cases_2')
                        }
                    }
                }

                stage('IT-ha_cases2') {
                    steps {
                        script {
                            run_single_it_test('ha_cases2')
                        }
                    }
                }

                stage('IT-ha_cases3') {
                    steps {
                        script {
                            run_single_it_test('ha_cases3')
                        }
                    }
                }

                stage('IT-ha_cases3_1') {
                    steps {
                        script {
                            run_single_it_test('ha_cases3_1')
                        }
                    }
                }

                stage('IT-ha_master') {
                    steps {
                        script {
                            run_single_it_test('ha_master')
                        }
                    }
                }

                stage('IT-handle_error') {
                    steps {
                        script {
                            run_single_it_test('handle_error')
                        }
                    }
                }

                stage('IT-handle_error_2') {
                    steps {
                        script {
                            run_single_it_test('handle_error_2')
                        }
                    }
                }

                stage('IT-handle_error_3') {
                    steps {
                        script {
                            run_single_it_test('handle_error_3')
                        }
                    }
                }

                stage('IT-i* group') {
                    steps {
                        script {
                            run_single_it_test('import_goroutine_leak incremental_mode initial_unit')
                        }
                    }
                }

                stage('IT-load_interrupt') {
                    steps {
                        script {
                            run_single_it_test('load_interrupt')
                        }
                    }
                }

                stage('IT-many_tables') {
                    steps {
                        script {
                            run_single_it_test('many_tables')
                        }
                    }
                }

                stage('IT-online_ddl') {
                    steps {
                        script {
                            run_single_it_test('online_ddl')
                        }
                    }
                }

                stage('IT-relay_interrupt') {
                    steps {
                        script {
                            run_single_it_test('relay_interrupt')
                        }
                    }
                }

                stage('IT-safe_mode group') {
                    steps {
                        script {
                            run_single_it_test('safe_mode sequence_safe_mode')
                        }
                    }
                }

                stage('IT-shardddl1') {
                    steps {
                        script {
                            run_single_it_test('shardddl1')
                        }
                    }
                }

                stage('IT-shardddl1_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl1_1')
                        }
                    }
                }

                stage('IT-shardddl2') {
                    steps {
                        script {
                            run_single_it_test('shardddl2')
                        }
                    }
                }

                stage('IT-shardddl2_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl2_1')
                        }
                    }
                }

                stage('IT-shardddl3') {
                    steps {
                        script {
                            run_single_it_test('shardddl3')
                        }
                    }
                }

                stage('IT-shardddl3_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl3_1')
                        }
                    }
                }

                stage('IT-shardddl4') {
                    steps {
                        script {
                            run_single_it_test('shardddl4')
                        }
                    }
                }

                stage('IT-shardddl4_1') {
                    steps {
                        script {
                            run_single_it_test('shardddl4_1')
                        }
                    }
                }

                stage('IT-sharding group') {
                    steps {
                        script {
                            run_single_it_test('sharding sequence_sharding')
                        }
                    }
                }

                stage('IT-start_task') {
                    steps {
                        script {
                            run_single_it_test('start_task')
                        }
                    }
                }

                stage('IT-status_and_apis') {
                    steps {
                        script {
                            run_single_it_test('print_status http_apis')
                        }
                    }
                }

                stage('IT-new_relay') {
                    steps {
                        script {
                            run_single_it_test('new_relay')
                        }
                    }
                }

                stage('IT-import_v10x') {
                    steps {
                        script {
                            run_single_it_test('import_v10x')
                        }
                    }
                }

                stage('IT-tls') {
                    steps {
                        script {
                            run_single_it_test('tls')
                        }
                    }
                }

                stage('IT-sharding2') {
                    steps {
                        script {
                            run_single_it_test('sharding2')
                        }
                    }
                }

                stage('IT-ha') {
                    steps {
                        script {
                            run_single_it_test('ha')
                        }
                    }
                }

                stage('IT-others') {
                    steps {
                        script {
                            run_single_it_test('others')
                        }
                    }
                }

            // END Integration Test
            }
        }

        stage('Coverage') {
            steps {
                run_make_coverage()
            }
        }

        stage('Print Summary') {
            steps {
                script {
                    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                    println "all test succeed time=${duration}"
                }
            }
        }
    }
}
