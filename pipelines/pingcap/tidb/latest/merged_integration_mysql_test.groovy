// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_mysql_test.yaml'
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
        GITHUB_TOKEN = credentials('github-bot-token')
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
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
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.base_sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    retry(3) {
                        sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                        sh label: 'download binary', script: """
                            chmod +x \${WORKSPACE}/scripts/PingCAP-QE/tidb-test/*.sh
                            \${WORKSPACE}/scripts/PingCAP-QE/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/* bin/
                            ls -alh bin/
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tidb-server -V
                        """
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: 'cache tidb-test', script: """
                        touch ws-${BUILD_TAG}
                        mkdir -p bin
                        cp -r ../tidb/bin/{pd,tidb,tikv}-server bin/ && chmod +x bin/*
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
                    }
                    axis {
                        name 'TEST_PART'
                        values '1', "2", "3", "4"
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
                        defaultContainer 'golang'
                    }
                } 
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                    ls -alh bin/
                                    ./bin/pd-server -V
                                    ./bin/tikv-server -V
                                    ./bin/tidb-server -V
                                    """
                                    container("golang") {
                                        sh label: "test_store=${TEST_STORE} cache_enabled=${CACHE_ENABLED} test_part=${TEST_PART}", script: """
                                            #!/usr/bin/env bash
                                            if [[ "${TEST_STORE}" == "tikv" ]]; then
                                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                                bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export CACHE_ENABLED=${CACHE_ENABLED}
                                                export TIKV_PATH="127.0.0.1:2379"
                                                export TIDB_TEST_STORE_NAME="tikv"
                                                cd mysql_test/ && ./test.sh -blacklist=1 -part=${TEST_PART}
                                            else
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export CACHE_ENABLED=${CACHE_ENABLED}
                                                export TIDB_TEST_STORE_NAME="unistore"
                                                cd mysql_test/ && ./test.sh -blacklist=1 -part=${TEST_PART}
                                            fi
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }        
        }
    }
}
