// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/integration-cdc-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_integration_cdc_test.yaml'
TIFLOW_COMMIT_ID = "master"

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
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${GIT_BASE_BRANCH}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_BASE_BRANCH ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/pingcap/tiflow.git",
                                    ]],
                                ]
                            )
                            script {
                                TIFLOW_COMMIT_ID = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                                println "tiflow latest commit on ${GIT_BASE_BRANCH}: ${TIFLOW_COMMIT_ID}"
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                parallel (
                    "tidb": {
                        dir('tidb') {
                            sh "git branch && git status"
                            cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${GIT_MERGE_COMMIT}") {
                                // FIXME: https://github.com/PingCAP-QE/tidb-test/issues/1987
                                sh label: 'tidb-server', script: """
                                ls bin/tidb-server || make
                                ./bin/tidb-server -V
                                """
                            }
                        }
                    },
                    "tiflow": {
                        dir('tiflow') {
                            sh "git branch && git status"
                            cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                                sh label: 'prepare cdc binary', script: """
                                ls bin/cdc || make cdc
                                ls bin/cdc.test || make integration_test_build
                                ls bin/cdc_kafka_consumer || make kafka_consumer
                                make check_failpoint_ctl
                                ls bin/
                                ./bin/cdc version
                                """
                            }
                        }
                    },
                )
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CASES'
                        values 'consistent_replicate_nfs', 'consistent_replicate_s3' , 'region_merge ddl_reentrant', 
                            'sink_retry capture_session_done_during_task', 'common_1 ddl_attributes'
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
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${GIT_MERGE_COMMIT}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                }
                            }
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                                    sh 'chmod +x ../scripts/pingcap/tiflow/*.sh'
                                    sh "${WORKSPACE}/scripts/pingcap/tiflow/ticdc_integration_test_download_dependency.sh master master master master http://fileserver.pingcap.net"
                                    sh label: "Case ${CASES}", script: """
                                    mv third_bin/* bin/ && ls -alh bin/
                                    rm -rf /tmp/tidb_cdc_test
                                    mkdir -p /tmp/tidb_cdc_test
                                    cp ../tidb/bin/tidb-server ./bin/
                                    ./bin/tidb-server -V
                                    ls -alh ./bin/
                                    make integration_test_mysql CASE="${CASES}"
                                    """             
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                    def log_tar_name = "${CASES}".replaceAll("\\s","-")
                                    sh label: "archive failure logs", script: """
                                    ls /tmp/tidb_cdc_test/
                                    tar -cvzf log-${log_tar_name}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")    
                                    ls -alh  log-${log_tar_name}.tar.gz  
                                    """
                                    archiveArtifacts(artifacts: "log-${log_tar_name}.tar.gz", caseSensitive: false)
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
