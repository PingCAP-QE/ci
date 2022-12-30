// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'wip/common-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_common_test.yaml'

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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_MERGE_COMMIT ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', GIT_BASE_BRANCH, "", GIT_CREDENTIALS_ID)
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
                        }
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh 'touch ws-${BUILD_TAG}'
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_DIR'
                        values 'jdbc_test'
                    }
                    axis {
                        name 'TEST_CMD'
                        values './test_fast.sh', "./test_slow.sh"
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
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/merged_integration_test/rev-${BUILD_TAG}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'  
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh """
                                        mkdir -p bin
                                        cp ${WORKSPACE}/tidb/bin/* bin/ && chmod +x bin/*
                                        ls -alh bin/
                                    """
                                    container("java") {
                                        sh label: "test_dir=${TEST_DIR} ${TEST_CMD}", script: """
                                            #!/usr/bin/env bash
                                            export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                            cd ${TEST_DIR} && ${TEST_CMD}
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
