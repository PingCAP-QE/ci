// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.5/pod-pull_mysql_client_test.yaml'
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
        GITHUB_TOKEN = credentials('github-bot-token')
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
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
                    sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                }
                dir('tidb-test') {
                    sh label: 'prepare', script: """
                        touch ws-${BUILD_TAG}
                        mkdir -p bin/
                        cp ../tidb/bin/tidb-server bin/ && chmod +x bin/tidb-server
                        ls -alh bin/
                        ./bin/tidb-server -V
                    """
                }
            }
        }
        stage('MySQL Connector Tests') {
            steps {
                container('mysql-client-test') {
                    dir('tidb-test') {
                        sh label: "run test", script: """#!/usr/bin/env bash
                            make mysql_client_test
                        """
                    }
                }
            }
        }
    }
}
