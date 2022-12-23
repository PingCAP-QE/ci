// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/integration-br-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_br_test.yaml'

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
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh "git branch && git status"
                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/br") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                        sh label: 'build-for-br-integration-test', script: 'make build_for_br_integration_test'
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CASES'
                        values 'br_incremental_ddl', 'br_incompatible_tidb_config', 
                            'br_log_restore', 'lightning_alter_random', 'lightning_new_collation', 'lightning_row-format-v2',
                            'lightning_s3', 'lightning_sqlmode', 'lightning_tiflash', 'br_s3', 'br_tiflash', 'br_tikv_outage', 
                            'br_tikv_outage2', 'lightning_disk_quota', 'br_300_small_tables',
                            'br_full_ddl', 'lightning_checkpoint', 'br_table_filter', 'br_systables', 'br_rawkv',
                            'br_key_locked', 'br_other', 'br_history', 'br_full', 'br_full_index'
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
                                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/br") {
                                        sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                        sh 'chmod +x ../scripts/pingcap/br/*.sh'
                                        sh "${WORKSPACE}/scripts/pingcap/br/integration_test_download_dependency.sh master master master master master http://fileserver.pingcap.net"
                                        sh label: "Case ${CASES}", script: """
                                            mv br/tests/*  tests/
                                            mkdir -p bin
                                            mv third_bin/* bin/ && ls -alh bin/
                                            rm -rf /tmp/backup_restore_test
                                            mkdir -p /tmp/backup_restore_test
                                            rm -rf cover
                                            mkdir cover
                                            export EXAMPLES_PATH=br/pkg/lightning/mydump/examples
                                            TEST_NAME=${CASES} tests/run.sh
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
    post {
        always {
            script {
                println "build url: ${env.BUILD_URL}"
                println "build blueocean url: ${env.RUN_DISPLAY_URL}"
                println "build name: ${env.JOB_NAME}"
                println "build number: ${env.BUILD_NUMBER}"
                println "build status: ${currentBuild.currentResult}"
            } 
        }
        // success {
        //     container('status-updater') {
        //         sh """
        //             set +x
        //             github-status-updater \
        //                 -action update_state \
        //                 -token ${GITHUB_TOKEN} \
        //                 -owner pingcap \
        //                 -repo tidb \
        //                 -ref  ${GIT_MERGE_COMMIT} \
        //                 -state success \
        //                 -context "${COMMIT_CONTEXT}" \
        //                 -description "test success" \
        //                 -url "${env.RUN_DISPLAY_URL}"
        //         """
        //     }
        // }

        // unsuccessful {
        //     container('status-updater') {
        //         sh """
        //             set +x
        //             github-status-updater \
        //                 -action update_state \
        //                 -token ${GITHUB_TOKEN} \
        //                 -owner pingcap \
        //                 -repo tidb \
        //                 -ref  ${GIT_MERGE_COMMIT} \
        //                 -state failure \
        //                 -context "${COMMIT_CONTEXT}" \
        //                 -description "test failed" \
        //                 -url "${env.RUN_DISPLAY_URL}"
        //         """
        //     }
        // }
    }
}
