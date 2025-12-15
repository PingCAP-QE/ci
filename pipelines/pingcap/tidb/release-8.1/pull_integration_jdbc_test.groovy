// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.1/pod-pull_integration_jdbc_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
prow.setPRDescription(REFS)

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
        // parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    container("golang") {
                        sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                        retry(3) {
                            script {
	                            component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master")
	                            component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master")
                            }
                        }
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: "prepare", script: """
                            touch ws-${BUILD_TAG}
                            mkdir -p bin
                            cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                            ls -alh bin/
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tidb-server -V
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PARAMS'
                        values 'jdbc8_test ./test_fast.sh', 'jdbc8_test ./test_slow.sh', 'mybatis_test ./test.sh',
                            'jooq_test ./test.sh', 'tidb_jdbc_test/tidb_jdbc_unique_test ./test.sh',
                            'tidb_jdbc_test/tidb_jdbc8_test ./test_fast.sh', 'tidb_jdbc_test/tidb_jdbc8_test ./test_slow.sh',
                            'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_slow.sh', 'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_tls.sh'
                    }
                    axis {
                        name 'TEST_STORE'
                        values "tikv"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'java'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh label: "print version", script: """
                                        ls -alh bin/
                                        ./bin/pd-server -V
                                        ./bin/tikv-server -V
                                        ./bin/tidb-server -V
                                    """
                                    container("java") {
                                        sh label: "test_params=${TEST_PARAMS} ", script: """#!/usr/bin/env bash
                                            params_array=(\${TEST_PARAMS})
                                            TEST_DIR=\${params_array[0]}
                                            TEST_SCRIPT=\${params_array[1]}
                                            echo "TEST_DIR=\${TEST_DIR}"
                                            echo "TEST_SCRIPT=\${TEST_SCRIPT}"

                                            if [[ "${TEST_STORE}" == "tikv" ]]; then
                                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                                bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIKV_PATH="127.0.0.1:2379"
                                                export TIDB_TEST_STORE_NAME="tikv"
                                                cd \${TEST_DIR} && chmod +x *.sh && \${TEST_SCRIPT}
                                            else
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIDB_TEST_STORE_NAME="unistore"
                                                cd \${TEST_DIR} && chmod +x *.sh && \${TEST_SCRIPT}
                                            fi
                                        """
                                    }
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
