
def TIDB_BRANCH = ghprbTargetBranch

// parse tidb branch
def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIDB_BRANCH = "${m3[0][1]}"
}
m1 = null

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def ws = pwd()
    sh "curl -O https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib.groovy"
    def script_path = "${ws}/goversion-select-lib.groovy"
    def goversion_lib = load script_path
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb-test"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]     
                    )
            ],
            volumes: [
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


try {
    run_with_pod {
        container("golang") {
            def ws = pwd()
            def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
            def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${TIDB_BRANCH}/${tidb_sha1}/centos7/tidb-server.tar.gz"
            stage("Get commits and Set params") {
                println "TIDB_BRANCH: ${TIDB_BRANCH}"
                println "tidb_sha1: $tidb_sha1"
                println "tidb_url: $tidb_url"
                ghprbCommentBody = ghprbCommentBody + " /tidb-test=pr/$ghprbPullId"
                println "commentbody: $ghprbCommentBody"
                basic_params = [
                    string(name: "upstreamJob", value: "tidb_test_ghpr_mysql_test"),
                    string(name: "ghprbCommentBody", value: ghprbCommentBody),
                    string(name: "ghprbPullTitle", value: ghprbPullTitle),
                    string(name: "ghprbPullLink", value: ghprbPullLink),
                    string(name: "ghprbPullDescription", value: ghprbPullDescription),
                    string(name: "ghprbTargetBranch", value: TIDB_BRANCH),
                    string(name: "ghprbActualCommit", value: tidb_sha1)
                ]

                println "basic_params: $basic_params"
            }

            stage("Checkout") {
                parallel(
                    'tidb-test': {
                        dir("go/src/github.com/pingcap/tidb-test") {
                            checkout(changelog: false, poll: false, scm: [
                                $class: "GitSCM",
                                branches: [
                                    [name: "${ghprbActualCommit}"],
                                ],
                                userRemoteConfigs: [
                                    [
                                        url: "git@github.com:pingcap/tidb-test.git",
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        credentialsId: 'github-sre-bot-ssh',
                                    ]
                                ],
                                extensions: [
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout'],
                                ],
                            ])
                        }
                        stash includes: "go/src/github.com/pingcap/tidb-test/**", name: "tidb-test"
                    }, 
                    'tidb': {
                        dir("go/src/github.com/pingcap/tidb") {
                            deleteDir()
                            timeout(10) {
                                retry(3){
                                    deleteDir()
                                    sh """
                                    while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                    curl ${tidb_url} | tar xz
                                    """
                                }
                            }
                        }
                    }
                ) 
            }

            stage("Tests") {
                parallel(
                    "test1": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh alias alter_table alter_table1 alter_table_PK analyze ansi auto_increment bigint bool bug28940878 bug33509 bug46760 bug58669 builtin case charset comment_column2 comment_table composite_index concurrent_ddl count_distinct count_distinct2 create_database create_index create_table ctype_gbk date_formats date_time_ddl datetime_insert datetime_update daylight_saving_time ddl_i18n_utf8 decimal delete delete_myisam do drop ds_mrr-big echo error_simulation exec_selection expression_index field_length filesort filesort_json filter_single_col_idx_big func_bitwise_ops func_compress func_concat func_date_add_myisam func_default func_gconcat func_group_innodb func_math func_op func_str_myisam gcol_alter_table gcol_blocked_sql_funcs gcol_column_def_options gcol_dependenies_on_vcol gcol_illegal_expression gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select gcol_supported_sql_funcs gcol_view grant_dynamic grant_lowercase_fs groupby having heap_btree heap_btree_myisam histogram_singleton implicit_char_to_num_conversion in index index_merge1 index_merge2 index_merge_bug29952775 index_merge_delete index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans infoschema innodb_tmp_table_heap_to_disk insert insert_select insert_select_myisam insert_update invisible_indexes issue_11208 issue_165 issue_20571 issue_207 issue_227 issue_266 issue_294 join join-reorder join_crash json json_functions json_gcol key keywords lead_lag lead_lag_explain like limit limit_myisam long_tmpdir lowercase_fs_off lowercase_mixed_tmpdir lowercase_table2 lowercase_table4 lowercase_table5 lowercase_utf8 lowercase_view lpad mariadb_cte_nonrecursive mariadb_cte_recursive math metadata multi_update multi_update_innodb multi_update_tiny_hash mysql_replace nth null_key_all_innodb odbc operator opt_hint_timeout opt_hints_join_order opt_hints_subquery optimizer_bug12837084 order_by_limit orderby overflow packet_myisam parser parser_precedence partition_bug18198 partition_hash partition_innodb partition_list partition_locking_ps_protocol partition_range precedence prepare ps qualified regexp replace reset_connection role role2 roles-ddl roles-view roles_bugs_wildcard row savepoint savepoint2 savepoint_in_optimistic_txn savepoint_in_pessimistic_txn savepoint_issue_26288 select_qualified show show_check_cs_myisam show_profile show_variables single_delete_update sqllogic str_quoted strict strict_autoinc_2innodb sub_query sub_query_more subquery_antijoin subquery_bugs subquery_sj_innodb_all sum_distinct-big temp_table time timestamp_insert timestamp_update tpcc transaction_isolation_func truth_value_transform type type_binary type_decimal type_enum type_time type_timestamp type_timestamp_myisam type_uint type_unit type_varchar union update update_myisam update_stmt user_if_exists varbinary variable variables variables_dynamic_privs view_grant view_myisam warnings window_functions window_functions_big window_functions_bugs window_functions_interesting_orders window_min_max with_non_recursive with_recursive with_recursive_bugs xd
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "test2": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh alias alter_table alter_table1 alter_table_PK analyze ansi auto_increment bigint bool bug28940878 bug33509 bug46760 bug58669 builtin case charset comment_column2 comment_table composite_index concurrent_ddl count_distinct count_distinct2 create_database create_index create_table ctype_gbk date_formats date_time_ddl datetime_insert datetime_update daylight_saving_time ddl_i18n_utf8 decimal delete delete_myisam do drop ds_mrr-big echo error_simulation exec_selection expression_index field_length filesort filesort_json filter_single_col_idx_big func_bitwise_ops func_compress func_concat func_date_add_myisam func_default func_gconcat func_group_innodb func_math func_op func_str_myisam gcol_alter_table gcol_blocked_sql_funcs gcol_column_def_options gcol_dependenies_on_vcol gcol_illegal_expression gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select gcol_supported_sql_funcs gcol_view grant_dynamic grant_lowercase_fs groupby having heap_btree heap_btree_myisam histogram_singleton implicit_char_to_num_conversion in index index_merge1 index_merge2 index_merge_bug29952775 index_merge_delete index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans infoschema innodb_tmp_table_heap_to_disk insert insert_select insert_select_myisam insert_update invisible_indexes issue_11208 issue_165 issue_20571 issue_207 issue_227 issue_266 issue_294 join join-reorder join_crash json json_functions json_gcol key keywords lead_lag lead_lag_explain like limit limit_myisam long_tmpdir lowercase_fs_off lowercase_mixed_tmpdir lowercase_table2 lowercase_table4 lowercase_table5 lowercase_utf8 lowercase_view lpad mariadb_cte_nonrecursive mariadb_cte_recursive math metadata multi_update multi_update_innodb multi_update_tiny_hash mysql_replace nth null_key_all_innodb odbc operator opt_hint_timeout opt_hints_join_order opt_hints_subquery optimizer_bug12837084 order_by_limit orderby overflow packet_myisam parser parser_precedence partition_bug18198 partition_hash partition_innodb partition_list partition_locking_ps_protocol partition_range precedence prepare ps qualified regexp replace reset_connection role role2 roles-ddl roles-view roles_bugs_wildcard row savepoint savepoint2 savepoint_in_optimistic_txn savepoint_in_pessimistic_txn savepoint_issue_26288 select_qualified show show_check_cs_myisam show_profile show_variables single_delete_update sqllogic str_quoted strict strict_autoinc_2innodb sub_query sub_query_more subquery_antijoin subquery_bugs subquery_sj_innodb_all sum_distinct-big temp_table time timestamp_insert timestamp_update tpcc transaction_isolation_func truth_value_transform type type_binary type_decimal type_enum type_time type_timestamp type_timestamp_myisam type_uint type_unit type_varchar union update update_myisam update_stmt user_if_exists varbinary variable variables variables_dynamic_privs view_grant view_myisam warnings window_functions window_functions_big window_functions_bugs window_functions_interesting_orders window_min_max with_non_recursive with_recursive with_recursive_bugs xd
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "test3": {
                        run_with_pod {
                        container("golang") {
                            dir("go/src/github.com/pingcap/tidb") {
                                timeout(10) {
                                    retry(3){
                                        sh """
                                        while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 1; done
                                        curl ${tidb_url} | tar xz
                                        """
                                    }
                                }
                            }
                            unstash "tidb-test"
                            dir("go/src/github.com/pingcap/tidb-test") {
                                timeout(10) {
                                    if (ghprbTargetBranch == "master") {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh alias alter_table alter_table1 alter_table_PK analyze ansi auto_increment bigint bool bug28940878 bug33509 bug46760 bug58669 builtin case charset comment_column2 comment_table composite_index concurrent_ddl count_distinct count_distinct2 create_database create_index create_table ctype_gbk date_formats date_time_ddl datetime_insert datetime_update daylight_saving_time ddl_i18n_utf8 decimal delete delete_myisam do drop ds_mrr-big echo error_simulation exec_selection expression_index field_length filesort filesort_json filter_single_col_idx_big func_bitwise_ops func_compress func_concat func_date_add_myisam func_default func_gconcat func_group_innodb func_math func_op func_str_myisam gcol_alter_table gcol_blocked_sql_funcs gcol_column_def_options gcol_dependenies_on_vcol gcol_illegal_expression gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select gcol_supported_sql_funcs gcol_view grant_dynamic grant_lowercase_fs groupby having heap_btree heap_btree_myisam histogram_singleton implicit_char_to_num_conversion in index index_merge1 index_merge2 index_merge_bug29952775 index_merge_delete index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans infoschema innodb_tmp_table_heap_to_disk insert insert_select insert_select_myisam insert_update invisible_indexes issue_11208 issue_165 issue_20571 issue_207 issue_227 issue_266 issue_294 join join-reorder join_crash json json_functions json_gcol key keywords lead_lag lead_lag_explain like limit limit_myisam long_tmpdir lowercase_fs_off lowercase_mixed_tmpdir lowercase_table2 lowercase_table4 lowercase_table5 lowercase_utf8 lowercase_view lpad mariadb_cte_nonrecursive mariadb_cte_recursive math metadata multi_update multi_update_innodb multi_update_tiny_hash mysql_replace nth null_key_all_innodb odbc operator opt_hint_timeout opt_hints_join_order opt_hints_subquery optimizer_bug12837084 order_by_limit orderby overflow packet_myisam parser parser_precedence partition_bug18198 partition_hash partition_innodb partition_list partition_locking_ps_protocol partition_range precedence prepare ps qualified regexp replace reset_connection role role2 roles-ddl roles-view roles_bugs_wildcard row savepoint savepoint2 savepoint_in_optimistic_txn savepoint_in_pessimistic_txn savepoint_issue_26288 select_qualified show show_check_cs_myisam show_profile show_variables single_delete_update sqllogic str_quoted strict strict_autoinc_2innodb sub_query sub_query_more subquery_antijoin subquery_bugs subquery_sj_innodb_all sum_distinct-big temp_table time timestamp_insert timestamp_update tpcc transaction_isolation_func truth_value_transform type type_binary type_decimal type_enum type_time type_timestamp type_timestamp_myisam type_uint type_unit type_varchar union update update_myisam update_stmt user_if_exists varbinary variable variables variables_dynamic_privs view_grant view_myisam warnings window_functions window_functions_big window_functions_bugs window_functions_interesting_orders window_min_max with_non_recursive with_recursive with_recursive_bugs xd
                                        pwd && ls -l
                                        """
                                        sh """
                                        pwd && ls -l
                                        cp mysql_test/result.xml test3_result.xml
                                        """
                                        junit testResults: "**/test3_result.xml"
                                    } else {
                                        sh """
                                        export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                        export TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server
                                        cd mysql_test && ./build.sh
                                        ./test.sh
                                        pwd && ls -l
                                        """
                                    }
                                }
                            }
                        }
                        }
                    },
                    "trigger tidb_ghpr_mysql_test": {
                        def tidb_test_download_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
                        println "check if current commit is already build, if not wait for build done."
                        timeout(10) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_download_url}; do sleep 3; done
                            echo "tidb_test build finished: ${ghprbActualCommit}"
                            """
                        }
                        def built = build(job: "tidb_ghpr_mysql_test", propagate: false, parameters: basic_params, wait: true)
                        println "https://ci.pingcap.net/blue/organizations/jenkins/tidb_ghpr_mysql_test/detail/tidb_ghpr_mysql_test/${built.number}/pipeline"
                        if (built.getResult() != 'SUCCESS') {
                            error "mysql_test failed"
                        }
                    }
                )
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
