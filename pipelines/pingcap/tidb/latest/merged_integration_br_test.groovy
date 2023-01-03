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
                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/br") {
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
                        values 'br_full br_full_index', 'br_key_locked br_other br_history', 'br_table_filter br_systables br_rawkv', 'br_300_small_tables br_full_ddl',
                            'br_incompatible_tidb_config lightning_checkpoint', 'lightning_new_collation', 'br_s3', 'br_tiflash', 'br_tikv_outage', 'br_tikv_outage2',
                            'lightning_disk_quota', 'br_azblob br_backup_empty  br_backup_version br_cache_table br_case_sensitive br_charset_gbk', 
                            'br_check_new_collocation_enable br_clustered_index br_crypter br_crypter2 br_db br_db_online', 'br_db_online_newkv br_db_skip br_debug_meta  br_ebs br_foreign_key br_full_cluster_restore',
                            'br_incremental br_incremental_ddl br_incremental_index br_incremental_only_ddl br_incremental_same_table br_insert_after_restore', 
                            'br_log_test  br_move_backup br_range br_restore_TDE_enable  br_shuffle_leader br_shuffle_region', 
                            'br_single_table br_skip_checksum br_small_batch_size br_split_region_fail br_table_partition br_tidb_placement_policy',
                            'br_views_and_sequences br_z_gc_safepoint lightning_alter_random lightning_auto_columns lightning_auto_random_default lightning_black-white-list', 
                            'lightning_character_sets lightning_check_partial_imported lightning_checkpoint_chunks lightning_checkpoint_columns lightning_checkpoint_dirty_tableid lightning_checkpoint_engines', 
                            'lightning_checkpoint_engines_order lightning_checkpoint_error_destroy lightning_checkpoint_parquet lightning_checkpoint_timestamp  lightning_checksum_mismatch  lightning_cmdline_override',
                            'lightning_column_permutation lightning_common_handle lightning_compress lightning_concurrent-restore lightning_csv lightning_default-columns',
                            'lightning_disable_scheduler_by_key_range lightning_distributed_import lightning_duplicate_detection lightning_duplicate_resolution lightning_duplicate_resolution lightning_examples',
                            'lightning_examples lightning_examples lightning_examples  lightning_fail_fast_on_nonretry_err lightning_file_routing  lightning_generated_columns',
                            'lightning_ignore_columns  lightning_incremental lightning_issue_282 lightning_issue_410 lightning_issue_519 lightning_local_backend',
                            ' lightning_max_incr lightning_max_random lightning_no_schema lightning_parquet  lightning_partition_incremental lightning_partitioned-table',
                            'lightning_record_network lightning_reload_cert lightning_restore lightning_routes  lightning_row-format-v2 lightning_s3',
                            'lightning_shard_rowid lightning_shard_rowid lightning_sqlmode lightning_tidb_duplicate_data  lightning_tidb_rowid lightning_tiflash',
                            'lightning_too_many_columns lightning_tool_135 lightning_tool_1420 lightning_tool_1472 lightning_tool_241 lightning_unused_config_keys',
                            'lightning_various_types  lightning_view  lightning_write_limit'
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
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}") { 
                                    sh """git status && ls -alh""" 
                                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/br") {
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
