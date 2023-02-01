// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_common_test.yaml'
final REFS = prow.getJobRefs(params.PROW_DECK_URL, params.PROW_JOB_ID)

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
                    cache(path: "./", filter: '**/*', key: "git/${REFS.org}/${REFS.repo}/rev-${REFS.base_sha}", restoreKeys: ["git/${REFS.org}/${REFS.repo}/rev-"]) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.base_sha}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', REFS.base_ref, "", GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_test/rev-${BUILD_TAG}") {
                        container("golang") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                            sh label: 'download binary', script: """
                            chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                            ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/* bin/
                            ls -alh bin/
                            """
                        }
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh 'touch ws-${BUILD_TAG}'
                        sh """
                        cd tidb_test && ./build.sh && cd ..
                        cd randgen-test && ./build.sh && cd ..
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_DIR'
                        values 'randgen-test', "analyze_test", "tidb_test", "go-sql-test", "gorm_test"
                    }
                    axis {
                        name 'TEST_STORE'
                        values 'unistore', "tikv"
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
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_test/rev-${BUILD_TAG}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'  
                                    sh label: 'tikv-server', script: 'ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V'
                                    sh label: 'pd-server', script: 'ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V'  
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                        mkdir -p bin
                                        cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                        ls -alh bin/
                                    """
                                    container("golang") {
                                        sh label: "test_store=${TEST_STORE} test_dir=${TEST_DIR}", script: """
                                            #!/usr/bin/env bash
                                            if [[ "${TEST_STORE}" == "tikv" ]]; then
                                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                                bash ${WORKSPACE}/scripts/pingcap/tidb-test/start_tikv.sh
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIKV_PATH="127.0.0.1:2379"
                                                export TIDB_TEST_STORE_NAME="tikv"
                                                cd ${TEST_DIR} && ./test.sh
                                            else
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                                export TIDB_TEST_STORE_NAME="unistore"
                                                cd ${TEST_DIR} && ./test.sh
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
