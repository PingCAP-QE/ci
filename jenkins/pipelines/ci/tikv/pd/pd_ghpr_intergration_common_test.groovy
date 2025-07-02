echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__pd_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

def TIKV_BRANCH = ghprbTargetBranch
def TIDB_BRANCH = ghprbTargetBranch
def TIDB_TEST_BRANCH = ghprbTargetBranch

// parse tikv branch
def m1 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m1) {
    TIKV_BRANCH = "${m1[0][1]}"
}
m1 = null
println "TIKV_BRANCH=${TIKV_BRANCH}"

// parse pd branch
def m2 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m2) {
    TIDB_BRANCH = "${m2[0][1]}"
}
m2 = null
println "TIDB_BRANCH=${TIDB_BRANCH}"

// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_TEST_BRANCH=${TIDB_TEST_BRANCH}"

// if (TIDB_TEST_BRANCH == "release-3.0" || TIDB_TEST_BRANCH == "release-3.1") {
//   TIDB_TEST_BRANCH = "release-3.0"
// }

def pd_url = "${FILE_SERVER_URL}/download/builds/pingcap/pd/pr/${ghprbActualCommit}/centos7/pd-server.tar.gz"

def tidb_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1"

@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    }
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc", "auto-scaling-improvement"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

try {
    stage('Prepare') {
        def prepares = [:]

        prepares["Part #1"] = {
            node("${GO_BUILD_SLAVE}") {
                def ws = pwd()
                deleteDir()
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                container("golang") {
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                    dir("go/src/github.com/pingcap/tidb") {
                        timeout(30) {
                            retry(3){
                                deleteDir()
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                                curl ${tidb_url} | tar xz
                                """
                            }
                        }
                    }

                    dir("go/src/github.com/PingCAP-QE/tidb-test") {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                        timeout(30) {
                            sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                            curl ${tidb_test_url} | tar xz

                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd tidb_test && GOPATH=${ws}/go ./build.sh && cd ..
                            cd randgen-test && GOPATH=${ws}/go ./build.sh && cd ..
                            """
                        }
                    }
                }

                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/_helper.sh", name: "helper"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/tidb_test/**", name: "tidb_test"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/randgen-test/**", name: "randgen-test"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/go-sql-test/**", name: "go-sql-test"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/go.*,go/src/github.com/PingCAP-QE/tidb-test/util/**,go/src/github.com/PingCAP-QE/tidb-test/bin/**", name: "tidb-test"
            }
        }

        prepares["Part #2"] = {
            node("${GO_BUILD_SLAVE}") {
               def ws = pwd()
                deleteDir()
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"


                container("golang") {
                    def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                    def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                    dir("go/src/github.com/pingcap/tidb") {
                        timeout(30) {
                            retry(3){
                                deleteDir()
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                                curl ${tidb_url} | tar xz
                                """
                            }
                        }
                    }

                    dir("go/src/github.com/PingCAP-QE/tidb-test") {
                        container("golang") {
                            def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/PingCAP-QE/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                            def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                            def tidb_test_url = "${FILE_SERVER_URL}/download/builds/PingCAP-QE/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"

                            timeout(30) {
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 15; done
                                curl ${tidb_test_url} | tar xz

                                mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                                cd mysql_test && GOPATH=${ws}/go ./build.sh && cd ..
                                cd analyze_test && GOPATH=${ws}/go ./build.sh && cd ..
                                """
                            }
                        }
                    }
                }

                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/_vendor/**", name: "tidb-test-vendor"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/mysql_test/**", name: "mysql_test"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/analyze_test/**", name: "analyze_test"
                stash includes: "go/src/github.com/PingCAP-QE/tidb-test/gorm_test/**", name: "gorm_test"
            }
        }

        parallel prepares
    }

    stage('Integration Common Test') {
        def tests = [:]

        def run = { test_dir, mytest, test_cmd ->
            node("${GO_TEST_SLAVE}") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                def ws = pwd()
                deleteDir()
                unstash "tidb-test"
                unstash "tidb-test-vendor"
                unstash "helper"
                unstash "${test_dir}"

                dir("go/src/github.com/PingCAP-QE/tidb-test/${test_dir}") {
                    container("golang") {
                        def tidb_sha1 = sh(returnStdout: true, script: "curl ${tidb_refs}").trim()
                        def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"

                        def tikv_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"
                        def tikv_sha1 = sh(returnStdout: true, script: "curl ${tikv_refs}").trim()
                        tikv_url = "${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"

                        timeout(30) {
                            retry(3){
                                sh """
                                while ! curl --output /dev/null --silent --head --fail ${tikv_url}; do sleep 15; done
                                curl ${tikv_url} | tar xz

                                while ! curl --output /dev/null --silent --head --fail ${pd_url}; do sleep 15; done
                                curl ${pd_url} | tar xz ./bin

                                mkdir -p ./tidb-src
                                while ! curl --output /dev/null --silent --head --fail ${tidb_url}; do sleep 15; done
                                curl ${tidb_url} | tar xz -C ./tidb-src
                                ln -s \$(pwd)/tidb-src "${ws}/go/src/github.com/pingcap/tidb"

                                mv tidb-src/bin/tidb-server ./bin/tidb-server
                                """
                            }
                        }

                        try {
                            timeout(10) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                rm -rf ./tikv ./pd
                                set -e

                                bin/pd-server --name=pd --data-dir=pd &>pd_${mytest}.log &
                                sleep 20
                                echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml

                                bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>tikv_${mytest}.log &
                                sleep 20

                                mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                                export log_level=debug
                                TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
                                TIKV_PATH='127.0.0.1:2379' \
                                TIDB_TEST_STORE_NAME=tikv \
                                GOPATH=${ws}/go \
                                ${test_cmd}
                                """
                            }
                        } catch (err) {
                            sh """
                            cat pd_${mytest}.log
                            cat tikv_${mytest}.log
                            cat tidb*.log mysql*.out 2>/dev/null
                            """
                            throw err
                        } finally {
                            sh """
                            set +e
                            killall -9 -r tidb-server
                            killall -9 -r tikv-server
                            killall -9 -r pd-server
                            set -e
                            """
                        }
                    }
                }
            }
        }

        tests["Integration Randgen Test"] = {
            run("randgen-test", "randgentest", "./test.sh")
        }

        tests["Integration Analyze Test"] = {
            run("analyze_test", "analyzetest", "./test.sh")
        }

        tests["Integration TiDB Test 1"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_1.t ./test.sh")
        }

        tests["Integration TiDB Test 2"] = {
            run("tidb_test", "tidbtest", "TEST_FILE=ql_2.t ./test.sh")
        }

        tests["Integration Go SQL Test"] = {
            run("go-sql-test", "gosqltest", "./test.sh")
        }

        tests["Integration GORM Test"] = {
            run("gorm_test", "gormtest", "./test.sh")
        }

        tests["Integration MySQL Test"] = {
            if (TIDB_TEST_BRANCH == "master") {
                run("mysql_test", "mysqltest", "./test.sh alias alter_table alter_table1 alter_table_PK auto_increment bigint bool builtin case charset comment_column2 comment_table composite_index concurrent_ddl count_distinct count_distinct2 create_database create_index create_table ctype_gbk date_formats date_time_ddl datetime_insert datetime_update daylight_saving_time ddl_i18n_utf8 decimal delete do drop echo exec_selection expression_index field_length func_concat gcol_alter_table gcol_blocked_sql_funcs gcol_column_def_options gcol_dependenies_on_vcol gcol_illegal_expression gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select gcol_supported_sql_funcs gcol_view grant_dynamic groupby having in index index_merge1 index_merge2 index_merge_delete index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans infoschema insert insert_select insert_update invisible_indexes issue_11208 issue_165 issue_20571 issue_207 issue_227 issue_266 issue_294 join-reorder join json json_functions json_gcol key like mariadb_cte_nonrecursive mariadb_cte_recursive math multi_update mysql_replace nth operator opt_hint_timeout orderby partition_bug18198 partition_hash partition_innodb partition_list partition_range precedence prepare ps qualified regexp replace role role2 row select_qualified show single_delete_update sqllogic str_quoted sub_query sub_query_more temp_table time timestamp_insert timestamp_update tpcc transaction_isolation_func type type_binary type_decimal type_enum type_time type_timestamp type_uint type_varchar union update update_stmt variable variables window_functions window_min_max with_non_recursive with_recursive with_recursive_bugs xd")
            } else {
                run("mysql_test", "mysqltest", "./test.sh")
            }
        }

        tests["Integration MySQL Test Cached"] = {
            if (TIDB_TEST_BRANCH == "master") {
                run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh alias alter_table alter_table1 alter_table_PK auto_increment bigint bool builtin case charset comment_column2 comment_table composite_index concurrent_ddl count_distinct count_distinct2 create_database create_index create_table ctype_gbk date_formats date_time_ddl datetime_insert datetime_update daylight_saving_time ddl_i18n_utf8 decimal delete do drop echo exec_selection expression_index field_length func_concat gcol_alter_table gcol_blocked_sql_funcs gcol_column_def_options gcol_dependenies_on_vcol gcol_illegal_expression gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select gcol_supported_sql_funcs gcol_view grant_dynamic groupby having in index index_merge1 index_merge2 index_merge_delete index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans infoschema insert insert_select insert_update invisible_indexes issue_11208 issue_165 issue_20571 issue_207 issue_227 issue_266 issue_294 join-reorder join json json_functions json_gcol key like mariadb_cte_nonrecursive mariadb_cte_recursive math multi_update mysql_replace nth operator opt_hint_timeout orderby partition_bug18198 partition_hash partition_innodb partition_list partition_range precedence prepare ps qualified regexp replace role role2 row select_qualified show single_delete_update sqllogic str_quoted sub_query sub_query_more temp_table time timestamp_insert timestamp_update tpcc transaction_isolation_func type type_binary type_decimal type_enum type_time type_timestamp type_uint type_varchar union update update_stmt variable variables window_functions window_min_max with_non_recursive with_recursive with_recursive_bugs xd")
            } else {
                run("mysql_test", "mysqltest", "CACHE_ENABLED=1 ./test.sh")
            }
        }

        parallel tests
    }

    currentBuild.result = "SUCCESS"
}
catch(Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
finally {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
    "${ghprbPullLink}" + "\n" +
    "${ghprbPullDescription}" + "\n" +
    "Integration Common Test Result: `${currentBuild.result}`" + "\n" +
    "Elapsed Time: `${duration} mins` " + "\n" +
    "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

stage("upload status"){
    node("master"){
        sh """curl --connect-timeout 2 --max-time 4 -d '{"job":"$JOB_NAME","id":$BUILD_NUMBER}' http://172.16.5.13:36000/api/v1/ci/job/sync || true"""
    }
}

if (params.containsKey("triggered_by_upstream_ci")) {
    stage("update commit status") {
        node("master") {
            if (currentBuild.result == "ABORTED") {
                PARAM_DESCRIPTION = 'Jenkins job aborted'
                // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                PARAM_STATUS = 'error'
            } else if (currentBuild.result == "FAILURE") {
                PARAM_DESCRIPTION = 'Jenkins job failed'
                PARAM_STATUS = 'failure'
            } else if (currentBuild.result == "SUCCESS") {
                PARAM_DESCRIPTION = 'Jenkins job success'
                PARAM_STATUS = 'success'
            } else {
                PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                PARAM_STATUS = 'error'
            }
            def default_params = [
                    string(name: 'PD_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-pd/integration-common-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "pd_update_commit_status", parameters: default_params, wait: true)
        }
    }
}
