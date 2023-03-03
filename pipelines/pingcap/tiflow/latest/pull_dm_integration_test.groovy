// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_integration_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} -c golang -- bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("prepare") {
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            cd ../tiflow && ./dm/tests/download-integration-test-binaries.sh master && ls -alh ./bin
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                        """
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", filter: '**/*', key: "git/pingcap/tiflow/dm-integration-test-binarys-${ghprbActualCommit}") { 
                        // build dm-master.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        // TODO: how to update cached binarys if needed
                        sh label: "prepare", script: """
                            [ -f ./bin/dm-master.test ] || make dm_integration_test_build
                            [ -d ./bin/dm-test-tools ] || make dm_integration_test_build
                            mkdir -p ./bin/dm-test-tools && mv ./dm/tests/bin/* ./bin/dm-test-tools
                            ls -alh ./bin
                            ls -alh ./bin/dm-test-tools
                            which ./bin/dm-master.test
                            which ./bin/dm-syncer.test
                            which ./bin/dm-worker.test
                            which ./bin/dmctl.test
                        """
                    }
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") { 
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        // values "all_mode", "ha_cases", "dmctl_advance dmctl_basic dmctl_command", "ha_cases_1", "ha_cases_2", "ha_cases2", 
                        //     "ha_cases3", "ha_cases3_1", "ha_master", "handle_error", "handle_error_2", "handle_error_3", "import_goroutine_leak incremental_mode initial_unit",
                        //     "load_interrupt", "many_tables", "online_ddl", "relay_interrupt", "safe_mode sequence_safe_mode", "shardddl1",
                        //     "shardddl1_1", "shardddl2", "shardddl2_1", "shardddl3", "shardddl3_1", "shardddl4", "shardddl4_1", "sharding sequence_sharding",
                        //     "start_task", "print_status http_apis", "new_relay", "import_v10x", "sharding2", "ha", "others", "others_2", "others_3"
                        values "ha_cases_1 ha_cases_2 ha_cases2", "ha_cases3 ha_cases3_1 ha_master", "handle_error handle_error_2 handle_error_3"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment { 
                            DM_CODECOV_TOKEN = credentials('codecov-token-tiflow') 
                            DM_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')    
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") { 
                                    timeout(time: 10, unit: 'MINUTES') {
                                        sh label: "wait mysql ready", script: """
                                            pwd && ls -alh
                                            # export MYSQL_HOST="127.0.0.1"
                                            # export MYSQL_PORT="3306"
                                            # ./dm/tests/wait_for_mysql.sh
                                            # export MYSQL_PORT="3307"
                                            # ./dm/tests/wait_for_mysql.sh
                                            # wait for mysql container ready.
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                        """
                                    }
                                    sh label: "${TEST_GROUP}", script: """
                                        export PATH=/usr/local/go/bin:\$PATH
                                        mkdir -p ./dm/tests/bin && cp -r ./bin/dm-test-tools/* ./dm/tests/bin/
                                        make dm_integration_test CASE="${TEST_GROUP}"  
                                    """
                                } 
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/dm_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/dm_test/ -type f -name "*.log")    
                                    ls -alh  log-${TEST_GROUP}.tar.gz  
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true, allowEmptyArchive: true
                            }
                        }
                    }
                }
            }        
        }
    }
}
