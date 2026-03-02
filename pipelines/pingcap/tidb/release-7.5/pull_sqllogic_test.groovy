// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-7.5/pod-pull_sqllogic_test.yaml'
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
                    env
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
                    }
                }
                dir('tidb-test') {
                    cache(path: "./sqllogic_test", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: 'prepare tidb-test', script: """
                            touch ws-${BUILD_TAG}
                            cd sqllogic_test && ./build.sh
                            cp ${WORKSPACE}/tidb/bin/tidb-server ./
                            chmod +x tidb-server && ./tidb-server -V
                        """
                    }
                }
            }
        }
        stage('TestsGroup1') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
                    }
                    axis {
                        name 'TEST_PATH_STRING'
                        values 'index/between/1 index/between/10 index/between/100', 'index/between/100 index/between/1000 index/commute/10',
                            'index/commute/100 index/commute/1000_n1 index/commute/1000_n2',
                            'index/delete/1 index/delete/10 index/delete/100 index/delete/1000 index/delete/10000',
                            'random/aggregates_n1 random/aggregates_n2 random/expr' , 'random/expr random/select_n1 random/select_n2',
                            'select random/groupby'
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
                                cache(path: "./sqllogic_test", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh label: "print version", script: """
                                        ls -alh sqllogic_test/
                                        ./sqllogic_test/tidb-server -V
                                    """
                                    container("golang") {
                                        sh label: "test_path: ${TEST_PATH_STRING}, cache_enabled:${CACHE_ENABLED}", script: """#!/usr/bin/env bash
                                            cd sqllogic_test/
                                            env
                                            ulimit -n
                                            sed -i '3i\\set -x' test.sh

                                            path_array=(${TEST_PATH_STRING})
                                            for path in \${path_array[@]}; do
                                                echo "test path: \${path}"
                                                export SQLLOGIC_TEST_PATH="/git/sqllogictest/test/\${path}"
                                                export TIDB_PARALLELISM=8
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/sqllogic_test/tidb-server"
                                                export CACHE_ENABLED="${CACHE_ENABLED}"
                                                ./test.sh
                                            done
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('TestsGroup2') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
                    }
                    axis {
                        name 'TEST_PATH_STRING'
                        values 'index/in/10 index/in/1000_n1 index/in/1000_n2', 'index/orderby/10 index/orderby/100',
                            'index/orderby/1000_n1 index/orderby/1000_n2', 'index/orderby_nosort/10 index/orderby_nosort/100',
                            'index/orderby_nosort/1000_n1 index/orderby_nosort/1000_n2', 'index/random/10 index/random/100 index/random/1000'
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
                                cache(path: "./sqllogic_test", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh label: "print version", script: """
                                        ls -alh sqllogic_test/
                                        ./sqllogic_test/tidb-server -V
                                    """
                                    container("golang") {
                                        sh label: "test_path: ${TEST_PATH_STRING}, cache_enabled:${CACHE_ENABLED}", script: """#!/usr/bin/env bash
                                            cd sqllogic_test/
                                            env
                                            ulimit -n
                                            sed -i '3i\\set -x' test.sh

                                            path_array=(${TEST_PATH_STRING})
                                            for path in \${path_array[@]}; do
                                                echo "test path: \${path}"
                                                export SQLLOGIC_TEST_PATH="/git/sqllogictest/test/\${path}"
                                                export TIDB_PARALLELISM=8
                                                export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/sqllogic_test/tidb-server"
                                                export CACHE_ENABLED="${CACHE_ENABLED}"
                                                ./test.sh
                                            done
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
