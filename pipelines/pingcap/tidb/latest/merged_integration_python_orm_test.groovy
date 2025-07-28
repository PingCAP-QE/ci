// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_python_orm_test.yaml'
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
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
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
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.base_sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
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
                    cache(path: "./bin", includes: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.base_sha}") {
                        sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                    }
                }
                dir('tidb-test') {
                    dir('bin') {
                        container('utils') {
                            sh label: 'download binary', script: """
                                script="${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \$script --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            """
                        }
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: 'cache tidb-test', script: """
                            cp -r ../tidb/bin/tidb-server bin/
                            touch ws-${BUILD_TAG}
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
                        values 'django_test/django-orm-test ./test.sh', 'sqlalchemy_test/sqlalchemy-test ./test.sh'
                    }
                    axis {
                        name 'TEST_STORE'
                        values "unistore"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'python'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                    ls -alh bin/
                                    ./bin/pd-server -V
                                    ./bin/tikv-server -V
                                    ./bin/tidb-server -V
                                    """
                                    container("python") {
                                        sh label: "test_params=${TEST_PARAMS} ", script: """#!/usr/bin/env bash
                                            set -- \${TEST_PARAMS}
                                            TEST_DIR=\$1
                                            TEST_SCRIPT=\$2
                                            echo "TEST_DIR=\${TEST_DIR}"
                                            echo "TEST_SCRIPT=\${TEST_SCRIPT}"

                                            export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                            export TIDB_TEST_STORE_NAME="unistore"
                                            cd \${TEST_DIR} && chmod +x *.sh && \${TEST_SCRIPT}
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
