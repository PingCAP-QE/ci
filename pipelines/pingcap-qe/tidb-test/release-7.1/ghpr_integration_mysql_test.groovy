// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'PingCAP-QE/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap-qe/tidb-test/release-7.1/pod-ghpr_integration_mysql_test.yaml'
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
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
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
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, trunkBranch=REFS.base_ref, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
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
                    cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                        sh label: 'tidb-server', script: 'make'
                        retry(2) {
                            sh label: 'download binary', script: """
                                chmod +x ${WORKSPACE}/scripts/artifacts/*.sh
                                ${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                                mv third_bin/* bin/
                                ls -alh bin/
                            """
                            sh label: "check binary", script: """
                                pwd && ls -alh
                                ls bin/tidb-server && ./bin/tidb-server -V
                                ls bin/pd-server && ./bin/pd-server -V
                                ls bin/tikv-server && ./bin/tikv-server -V
                            """
                        }
                    }
                }
                dir('tidb-test') {
                    cache(path: "./mysql_test", includes: '**/*', key: "ws/${BUILD_TAG}/mysql-test") {
                        sh "touch ws-${BUILD_TAG}"
                    }
                }
            }
        }
        stage('MySQL Tests') {
            matrix {
                axes {
                    axis {
                        name 'PART'
                        values '1', '2', '3', '4'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                                    sh label: "print version", script: """
                                        pwd && ls -alh
                                        ls bin/tidb-server && ./bin/tidb-server -V
                                        ls bin/pd-server && ./bin/pd-server -V
                                        ls bin/tikv-server && ./bin/tikv-server -V
                                    """
                                }
                            }
                            dir('tidb-test/mysql_test') {
                                sh """
                                    mkdir -p bin
                                    mv ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                    ls -alh bin/
                                """
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/mysql-test") {
                                    sh label: "PART ${PART}", script: """
                                        #!/usr/bin/env bash
                                        ls -alh
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                        export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/mysql_test/bin/tidb-server"
                                        export TIKV_PATH="127.0.0.1:2379"
                                        export TIDB_TEST_STORE_NAME="tikv"
                                        ./test.sh -blacklist=1 -part=${PART}
                                    """
                                }
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                        }
                    }
                }
            }
        }
    }
}
