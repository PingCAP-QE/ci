@NonCPS 
def extract_pull_id(MSG){
    def resp = []
    def m1 = MSG =~ /(\#\b\d+)/
    if (m1) {
        for (int i = 0; i < m1.count; i++ ) {
            println m1[i][0]
            resp.add(m1[i][0])
        }
    }
    m1 = null
    
    return resp.join(",")
}


node("github-status-updater") {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            TIDB_BRANCH = trimPrefix(ref)
            // def m1 = GEWT_COMMIT_MSG =~ /(?<=\()(.*?)(?=\))/
            // if (m1) {
            //     GEWT_PULL_ID = "${m1[0][1]}"
            //     GEWT_PULL_ID = GEWT_PULL_ID.substring(1)
            // }
            // m1 = null
            GEWT_PULL_ID = extract_pull_id(GEWT_COMMIT_MSG)

            echo "commit_msg=${GEWT_COMMIT_MSG}"
            echo "author=${GEWT_AUTHOR}"
            echo "author_email=${GEWT_AUTHOR_EMAIL}"
            echo "pull_id=${GEWT_PULL_ID}"
        } else {
            echo 'trigger manually'
            echo "param ref not exist"
        }

        if ( env.TIDB_COMMIT_ID == '') {
            echo "invalid param TIDB_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param TIDB_COMMIT_ID')
        }

        echo "COMMIT=${TIDB_COMMIT_ID}"
        echo "BRANCH=${TIDB_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "tidb_integration_test_ci"),
                booleanParam(name: 'release_test', value: true),
                string(name: 'release_test__release_branch', value: TIDB_BRANCH),
                string(name: 'release_test__tidb_commit', value: TIDB_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    try {
        stage("Build") {
            build(job: "tidb_merged_pr_build", parameters: default_params, wait: true)
        }
        stage("Trigger Test Job") {
            container("golang") {
                parallel(
                        // integration test
                        tidb_ghpr_integration_br_test: {
                            build(job: "tidb_ghpr_integration_br_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_common_test: {
                            build(job: "tidb_ghpr_common_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_integration_common_test: {
                            build(job: "tidb_ghpr_integration_common_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_integration_campatibility_test: {
                            build(job: "tidb_ghpr_integration_campatibility_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_integration_copr_test: {
                            build(job: "tidb_ghpr_integration_copr_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_integration_ddl_test: {
                            build(job: "tidb_ghpr_integration_ddl_test", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_mybatis: {
                            build(job: "tidb_ghpr_mybatis", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_sqllogic_test_1: {
                            build(job: "tidb_ghpr_sqllogic_test_1", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_sqllogic_test_2: {
                            build(job: "tidb_ghpr_sqllogic_test_2", parameters: default_params, wait: true)
                        },
                        tidb_ghpr_tics_test: {
                            build(job: "tidb_ghpr_tics_test", parameters: default_params, wait: true)
                        },

                        // coverage
                        tidb_ghpr_integration_br_test: {
                            build(job: "tidb_ghpr_coverage", parameters: default_params, wait: true)
                        },
                        // unit test
                        // tidb_ghpr_unit_test: {
                        //     build(job: "tidb_ghpr_unit_test", parameters: default_params, wait: true)
                        // },
                        // tidb_ghpr_check: {
                        //     build(job: "tidb_ghpr_check", parameters: default_params, wait: true)
                        // },
                        // tidb_ghpr_check_2: {
                        //     build(job: "tidb_ghpr_check_2", parameters: default_params, wait: true)
                        // },
                )
            }
        }

        currentBuild.result = "SUCCESS"
    } catch(Exception e) {
        currentBuild.result = "FAILURE"
        println "catch_exception Exception"
        println e
    } finally {
        stage("alert") {
            container("python3") {
                if ( env.REF != '' ) {
                    sh """
                cat > env_param.conf <<EOF
export BRANCH=${TIDB_BRANCH}
export COMMIT_ID=${TIDB_COMMIT_ID}
export AUTHOR=${GEWT_AUTHOR}
export AUTHOR_EMAIL=${GEWT_AUTHOR_EMAIL}
export PULL_ID=${GEWT_PULL_ID}
EOF
                """
                }  else {
                    sh """
                cat > env_param.conf <<EOF
export BRANCH=${TIDB_BRANCH}
export COMMIT_ID=${TIDB_COMMIT_ID}
EOF
                """
                }
                sh "cat env_param.conf"
                sh "curl -LO ${FILE_SERVER_URL}/download/cicd/scripts/tidb_integration_test_ci_alert_v2.py"

                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'GITHUB_API_TOKEN'),
                                 string(credentialsId: 'feishu-ci-report-integration-test', variable: "FEISHU_ALERT_URL")
                ]) {
                    sh '''#!/bin/bash
                set +x
                export GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
                export FEISHU_ALERT_URL=${FEISHU_ALERT_URL}
                source env_param.conf
                python3 tidb_integration_test_ci_alert_v2.py > alert_feishu.log
                set -x
                cat alert_feishu.log
                '''
                }
            }
        }
    }
}