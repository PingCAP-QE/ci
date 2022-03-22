echo "release test: ${params.containsKey("release_test")}"
if (params.containsKey("release_test")) {
    ghprbTargetBranch = params.getOrDefault("release_test__ghpr_target_branch", params.release_test__release_branch)
    ghprbCommentBody = params.getOrDefault("release_test__ghpr_comment_body", "")
    ghprbActualCommit = params.getOrDefault("release_test__ghpr_actual_commit", params.release_test__tidb_commit)
    ghprbPullId = params.getOrDefault("release_test__ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("release_test__ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("release_test__ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("release_test__ghpr_pull_description", "")
}

echo "trigger by upstream job: ${params.containsKey("upstreamJob")}"
if (params.containsKey("upstreamJob")) {
    upstreamJob = params.get("upstreamJob")
    println "upstreamJob: ${upstreamJob}"
    ghprbTargetBranch=params.getOrDefault("ghprbTargetBranch", "")
    ghprbCommentBody=params.getOrDefault("ghprbCommentBody", "")
    ghprbActualCommit=params.getOrDefault("ghprbActualCommit", "")
    ghprbPullId=params.getOrDefault("ghprbPullId", "")
    ghprbPullTitle=params.getOrDefault("ghprbPullTitle", "")
    ghprbPullLink=params.getOrDefault("ghprbPullLink", "")
    ghprbPullDescription=params.getOrDefault("ghprbPullDescription", "")
    println "ghprbTargetBranch: ${ghprbTargetBranch}"
    println "ghprbCommentBody: ${ghprbCommentBody}"
    println "ghprbActualCommit: ${ghprbActualCommit}"
}


CI_RUN_PART_TEST_CASES = """
    with_non_recursive window_min_max temp_table mariadb_cte_recursive 
    mariadb_cte_nonrecursive json_functions gcol_view gcol_supported_sql_funcs 
    expression_index date_time_ddl show timestamp_insert 
    infoschema datetime_insert alias 
    alter_table alter_table_PK auto_increment 
    bigint bool builtin charset comment_table 
    composite_index concurrent_ddl count_distinct 
    count_distinct2 create_database create_index 
    create_table datetime_update daylight_saving_time 
    ddl_i18n_utf8 decimal do drop echo exec_selection 
    field_length func_concat gcol_alter_table 
    gcol_blocked_sql_funcs gcol_dependenies_on_vcol 
    gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select 
    grant_dynamic groupby having in index index_merge2 
    index_merge_delete insert insert_select issue_11208 issue_165 
    issue_20571 issue_207 issue_227 issue_266 issue_294 join json 
    like math mysql_replace operator orderby partition_bug18198 
    partition_hash partition_innodb partition_list partition_range 
    precedence prepare qualified regexp replace select_qualified 
    single_delete_update sqllogic str_quoted sub_query sub_query_more 
    time timestamp_update tpcc transaction_isolation_func type 
    type_binary type_uint union update update_stmt variable 
    with_recursive with_recursive_bugs xd
    index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans index_merge1
    """

// remove test: temp_table
if (ghprbTargetBranch in ["release-5.2"]) {
    CI_RUN_PART_TEST_CASES = """
    with_non_recursive window_min_max mariadb_cte_recursive 
    mariadb_cte_nonrecursive json_functions gcol_view gcol_supported_sql_funcs 
    expression_index date_time_ddl show timestamp_insert 
    infoschema datetime_insert alias 
    alter_table alter_table_PK auto_increment 
    bigint bool builtin charset comment_table 
    composite_index concurrent_ddl count_distinct 
    count_distinct2 create_database create_index 
    create_table datetime_update daylight_saving_time 
    ddl_i18n_utf8 decimal do drop echo exec_selection 
    field_length func_concat gcol_alter_table 
    gcol_blocked_sql_funcs gcol_dependenies_on_vcol 
    gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select 
    grant_dynamic groupby having in index  index_merge1 index_merge2 
    index_merge_delete insert insert_select issue_11208 issue_165 
    issue_20571 issue_207 issue_227 issue_266 issue_294 join json 
    like math mysql_replace operator orderby partition_bug18198 
    partition_hash partition_innodb partition_list partition_range 
    precedence prepare qualified regexp replace select_qualified 
    single_delete_update sqllogic str_quoted sub_query sub_query_more 
    time timestamp_update tpcc transaction_isolation_func type 
    type_binary type_uint union update update_stmt variable 
    with_recursive with_recursive_bugs xd
    index_merge_sqlgen_exprs index_merge_sqlgen_exprs_orandor_1_no_out_trans
    """
}

if (ghprbTargetBranch in ["release-5.1"]) {
    CI_RUN_PART_TEST_CASES = """
    with_non_recursive window_min_max mariadb_cte_recursive 
    mariadb_cte_nonrecursive json_functions gcol_view gcol_supported_sql_funcs 
    date_time_ddl show timestamp_insert 
    infoschema datetime_insert alias 
    alter_table alter_table_PK auto_increment 
    bool builtin composite_index concurrent_ddl count_distinct 
    count_distinct2 create_database create_index 
    create_table datetime_update daylight_saving_time 
    ddl_i18n_utf8 decimal do drop echo exec_selection 
    field_length func_concat gcol_alter_table 
    gcol_blocked_sql_funcs gcol_dependenies_on_vcol 
    gcol_ins_upd gcol_non_stored_columns gcol_partition gcol_select 
    grant_dynamic groupby having in index 
    insert insert_select issue_11208 issue_165 
    issue_20571 issue_207 issue_227 issue_266 issue_294 join json 
    like math mysql_replace operator orderby partition_bug18198 
    partition_hash partition_innodb partition_list partition_range 
    precedence prepare qualified regexp replace select_qualified 
    single_delete_update sqllogic str_quoted sub_query sub_query_more 
    time timestamp_update tpcc transaction_isolation_func type 
    type_binary type_uint union update update_stmt variable 
    with_recursive with_recursive_bugs xd
    """
}


def TIDB_TEST_BRANCH = ghprbTargetBranch
// parse tidb_test branch
def m3 = ghprbCommentBody =~ /tidb[_\-]test\s*=\s*([^\s\\]+)(\s|\\|$)/
if (m3) {
    TIDB_TEST_BRANCH = "${m3[0][1]}"
}
m3 = null
println "TIDB_TEST_BRANCH or PR: ${TIDB_TEST_BRANCH}"

GO_VERSION = "go1.18"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
]
POD_LABEL_MAP = [
    "go1.13": "tidb-ghpr-common-test-go1130-${BUILD_NUMBER}",
    "go1.16": "tidb-ghpr-common-test-go1160-${BUILD_NUMBER}",
    "go1.18": "tidb-ghpr-common-test-go1180-${BUILD_NUMBER}",
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

POD_NAMESPACE = "jenkins-tidb"

def tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
def tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${ghprbActualCommit}/centos7/done"
def TIDB_TEST_STASH_FILE = "tidb_test_mysql_test_${UUID.randomUUID().toString()}.tar"

echo "trigger by upstream job: ${params.containsKey("upstreamJob")}"
if (params.containsKey("upstreamJob")) {
    upstreamJob = params.get("upstreamJob")
    println "upstreamJob: ${upstreamJob}"
    tidb_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/tidb-server.tar.gz"
    tidb_done_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-check/pr/${ghprbActualCommit}/centos7/done"
}

if (ghprbTargetBranch in ["br-stream"]) {
    println "This PR is for feature branch"
    println "Skip mysql_test ci for feature branch: ${ghprbTargetBranch}"
    return 0
}

def run_test_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes"
    podTemplate(label: label,
            cloud: cloud,
            namespace: POD_NAMESPACE,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],  
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
            println "debug command:\nkubectl -n ${POD_NAMESPACE} exec -ti ${NODE_NAME} -- bash"
            body()
        }
    }
}

all_task_result = []

try {
    stage('Prepare') {
        run_test_with_pod {
            def ws = pwd()
            deleteDir()
            println "work space path:\n${ws}"
            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
                        while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                        curl ${tidb_url} | tar xz
                        """
                        }
                    }
                }

                dir("go/src/github.com/pingcap/tidb-test") {
                    timeout(10) {
                        def tidb_test_refs = "${FILE_SERVER_URL}/download/refs/pingcap/tidb-test/${TIDB_TEST_BRANCH}/sha1"
                        sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_refs}; do sleep 5; done
                        """
                        def tidb_test_sha1 = sh(returnStdout: true, script: "curl ${tidb_test_refs}").trim()
                        def tidb_test_url = "${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/${tidb_test_sha1}/centos7/tidb-test.tar.gz"
                        sh """
                            while ! curl --output /dev/null --silent --head --fail ${tidb_test_url}; do sleep 5; done
                            curl ${tidb_test_url} | tar xz
                            export TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb
                            cd mysql_test && ./build.sh && cd ..      
                        """

                        sh """
                            echo "stash tidb-test"
                            cd .. && tar -cf $TIDB_TEST_STASH_FILE tidb-test/
                            curl -F builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE}=@${TIDB_TEST_STASH_FILE} ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
            deleteDir()
        }
    }

    stage('MySQL Test') {
        run_test_with_pod {
            def ws = pwd()
            def test_dir = "mysql_test"
            def log_path = "mysql-test.out*"
            deleteDir()
            println "work space path:\n${ws}"
            println "run some mysql tests as below:\n ${CI_RUN_PART_TEST_CASES}"

            container("golang") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(10) {
                        retry(3){
                            deleteDir()
                            sh """
                                while ! curl --output /dev/null --silent --head --fail ${tidb_done_url}; do sleep 1; done
                                curl ${tidb_url} | tar xz
                            """
                        }
                    }
                }
                dir("go/src/github.com/pingcap") {
                    sh """
                        echo "unstash tidb"
                        curl ${FILE_SERVER_URL}/download/builds/pingcap/tidb-test/tmp/${TIDB_TEST_STASH_FILE} | tar x
                    """
                }

                dir("go/src/github.com/pingcap/tidb-test/${test_dir}") {
                    try {
                        timeout(10) {
                            if (ghprbTargetBranch in ["master"]) {
                                sh """
                                curl -o run-test-part.sh ${FILE_SERVER_URL}/download/cicd/tidb-mysql-test-ci/run-test-part.sh
                                chmod +x run-test-part.sh
                                """
                                sh """
                                export CI_RUN_PART_TEST_CASES=\"${CI_RUN_PART_TEST_CASES}\"
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./run-test-part.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                            } else if (ghprbTargetBranch in ["release-6.0"]) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                # TODO: those tests failed even on master branch, skip them for relase-6.0 branch
                                rm -rf t/variables
                                rm -rf t/case
                                rm -rf t/ctype_gbk
                                set -e
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                            } else if (ghprbTargetBranch.startsWith("release-") ) {
                                sh """
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./test.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                            } else {
                                sh """
                                curl -o run-test-part.sh ${FILE_SERVER_URL}/download/cicd/tidb-mysql-test-ci/run-test-part-without-all-arg.sh 
                                chmod +x run-test-part.sh
                                """
                                sh """
                                export CI_RUN_PART_TEST_CASES=\"${CI_RUN_PART_TEST_CASES}\"
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                TIDB_SERVER_PATH=${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server \
                                ./run-test-part.sh
                                
                                set +e
                                killall -9 -r tidb-server
                                killall -9 -r tikv-server
                                killall -9 -r pd-server
                                rm -rf /tmp/tidb
                                set -e
                                """
                            }
                        }
                    } catch (err) {
                        sh "cat ${log_path}"
                        sh """
                        set +e
                        killall -9 -r tidb-server
                        killall -9 -r tikv-server
                        killall -9 -r pd-server
                        rm -rf /tmp/tidb
                        set -e
                        """
                        throw err
                    }
                }
            }
        }
    }

    currentBuild.result = "SUCCESS"
} catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    println e
    // this ambiguous condition means a user probably aborted
    currentBuild.result = "ABORTED"
} catch (hudson.AbortException e) {
    println e
    // this ambiguous condition means during a shell step, user probably aborted
    if (e.getMessage().contains('script returned exit code 143')) {
        currentBuild.result = "ABORTED"
    } else {
        currentBuild.result = "FAILURE"
    }
} catch (InterruptedException e) {
    println e
    currentBuild.result = "ABORTED"
}
catch (Exception e) {
    if (e.getMessage().equals("hasBeenTested")) {
        currentBuild.result = "SUCCESS"
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
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
                    string(name: 'TIDB_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-tidb/mysql-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tidb_update_commit_status", parameters: default_params, wait: true)
        }
    }
}