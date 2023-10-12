// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_nodejs_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
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
                container(name: 'nodejs') {
                    sh label: 'Debug info', script: """
                        printenv
                        echo "-------------------------"
                        echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                    """
                }
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutV2('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, "")
                            }
                        }
                    }
                }
                dir(REFS.repo) {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    container('nodejs') {
                        cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/pull_integration_nodejs_test/rev-${BUILD_TAG}") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                            sh label: 'download binary', script: """
                                chmod +x ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/*.sh
                                ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                                mv third_bin/* bin/
                                ls -alh bin/
                            """
                        }
                    }
                }
            }
        }
        stage('Node.js Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_DIR'
                        values 'prisma_test', 'typeorm_test', 'sequelize_test'
                    }
                    axis {
                        name 'TEST_STORE'
                        values "tikv"
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'nodejs'
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                                    sh label: "print version", script: """
                                        pwd && ls -alh
                                        ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V
                                        ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V
                                        ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V
                                    """
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    sh """
                                        mkdir -p bin
                                        cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                        ls -alh bin/
                                    """
                                    sh label: "${TEST_DIR} ", script: """#!/usr/bin/env bash
                                        export TIDB_SERVER_PATH="\$(pwd)/bin/tidb-server"
                                        export TIDB_TEST_STORE_NAME="${TEST_STORE}"
                                        if [[ "${TEST_STORE}" == "tikv" ]]; then
                                            echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                            bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                            export TIKV_PATH="127.0.0.1:2379"
                                        fi

                                        cd \${TEST_DIR} && chmod +x *.sh && ./test.sh
                                    """
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
