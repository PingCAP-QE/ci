// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/integration-cdc-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_cdc_test.yaml'

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
                                make cdc
                                make integration_test_build
                                make kafka_consumer
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
                        values 'consistent_replicate_nfs', 'consistent_replicate_storage_s3 consistent_replicate_storage_file', 'processor_panic owner_resign',
                            'changefeed_error ddl_sequence', 'force_replicate_table multi_capture', 'kafka_big_messages cdc',
                            'drop_many_tables multi_cdc_cluster', 'processor_stop_delay capture_suicide_while_balance_table',  'row_format foreign_key',
                            'canal_json_basic ddl_puller_lag', 'partition_table changefeed_auto_stop', 'sorter charset_gbk', 'owner_remove_table_error', 
                            'bdr_mode clustered_index', 'multi_tables_ddl bank', 'multi_source kafka_sink_error_resume', 'sink_retry kv_client_stream_reconnect',
                            'consistent_replicate_gbk http_api', 'changefeed_fast_fail tidb_mysql_test', 'canal_json_adapter_compatibility processor_etcd_worker_delay',
                            'batch_update_to_no_batch gc_safepoint', 'default_value changefeed_pause_resume', 'cli simple cdc_server_tips', 
                            'changefeed_resume_with_checkpoint_ts ddl_reentrant', 'processor_err_chan resolve_lock move_table', 'kafka_compression autorandom',
                            'ddl_attributes many_pk_or_uk', 'kafka_messages capture_session_done_during_task', 'http_api_tls tiflash', 
                            'new_ci_collation_without_old_value region_merge common_1', 'split_region availability changefeed_reconstruct', 'http_proxies kill_owner_with_ddl',
                            'savepoint event_filter generate_column', 'syncpoint sequence', 'processor_resolved_ts_fallback big_txn', 'csv_storage_basic changefeed_finish',
                            'sink_hang canal_json_storage_basic', 'multi_topics new_ci_collation_with_old_value', 'batch_add_table multi_changefeed'
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
                                    sh "${WORKSPACE}/scripts/pingcap/tiflow/ticdc_integration_test_download_dependency.sh --pd=master --tikv=master --tiflash=master"
                                    sh label: "Case ${CASES}", script: """
                                    #!/usr/bin/env bash
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
