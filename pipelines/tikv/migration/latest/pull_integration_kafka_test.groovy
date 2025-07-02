// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'tikv/migration'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/tikv/migration/latest/pod-pull_integration_kafka_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
        timeout(time: 65, unit: 'MINUTES')
        parallelsAlwaysFailFast()
        skipDefaultCheckout()
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
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("migration") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                        sh """
                        git rev-parse --show-toplevel
                        git status
                        git status -s .
                        """
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('migration') {
                    cache(path: "./cdc", includes: '**/*', key: "ws/${BUILD_TAG}/tikvcdc") {
                        container("golang") {
                            sh label: 'integration test prepare', script: """#!/usr/bin/env bash
                            cd cdc/
                            make prepare_test_binaries
                            make check_third_party_binary
                            make integration_test_build
                            """
                        }
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'others'
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir('migration') {
                               cache(path: "./cdc", includes: '**/*', key: "ws/${BUILD_TAG}/tikvcdc") {
                                    sh "printenv"
                                    container("kafka") {
                                        timeout(time: 6, unit: 'MINUTES') {
                                            sh label: "Waiting for kafka ready", script: """
                                                echo "Waiting for zookeeper to be ready..."
                                                while ! nc -z localhost 2181; do sleep 10; done
                                                echo "Waiting for kafka to be ready..."
                                                while ! nc -z localhost 9092; do sleep 10; done
                                                echo "Waiting for kafka-broker to be ready..."
                                                while ! echo dump | nc localhost 2181 | grep brokers | awk '{\$1=\$1;print}' | grep -F -w "/brokers/ids/1"; do sleep 10; done
                                            """
                                        }
                                    }
                                    sh label: "TEST_GROUP ${TEST_GROUP}",script: """#!/usr/bin/env bash
                                        cd cdc/
                                        ./tests/integration_tests/run_group.sh kafka ${TEST_GROUP}
                                    """
                               }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tikv_cdc_test/
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tikv_cdc_test/ -maxdepth 2 -type f -name "*.log")
                                    ls -alh log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }
}
