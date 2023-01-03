// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/integration-br-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_integration_br_test.yaml'

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
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh "git branch && git status"
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/br-integration-test/rev-${BUILD_TAG}") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'build-for-br-integration-test', script: 'make build_for_br_integration_test'
                        sh label: "download dependency", script: """
                            chmod +x ../scripts/pingcap/br/*.sh
                            ${WORKSPACE}/scripts/pingcap/br/integration_test_download_dependency.sh master master master master master http://fileserver.pingcap.net
                            mv third_bin/* bin/
                            ls -alh bin/
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CASES'
                        values 'br_incremental_ddl br_incompatible_tidb_config', 'br_log_restore lightning_alter_random'
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
                        options { timeout(time: 25, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}") { 
                                    sh """git status && ls -alh""" 
                                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/br-integration-test/rev-${BUILD_TAG}") {
                                        sh label: 'tidb-server version', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'  
                                        sh label: 'tikv-server version', script: 'ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V'
                                        sh label: 'pd-server version', script: 'ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V' 
                                        sh label: "Case ${CASES}", script: """
                                            #!/usr/bin/env bash
                                            mv br/tests/*  tests/
                                            mkdir -p bin
                                            mv third_bin/* bin/ && ls -alh bin/
                                            caseArray=(\${CASES})
                                            for CASE in \${caseArray[@]}; do
                                                if [[ ! -e tests/\${CASE}/run.sh ]]; then
                                                    echo \${CASE} not exists, skip.
                                                    break
                                                fi
                                                echo "Running test case: \$CASE"
                                                rm -rf /tmp/backup_restore_test
                                                mkdir -p /tmp/backup_restore_test
                                                rm -rf cover
                                                mkdir cover
                                                export EXAMPLES_PATH=br/pkg/lightning/mydump/examples
                                                TEST_NAME=\$CASE tests/run.sh
                                            done
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
