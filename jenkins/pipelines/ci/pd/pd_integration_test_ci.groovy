node("github-status-updater") {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            PD_BRANCH = trimPrefix(ref)
            def m1 = GEWT_COMMIT_MSG =~ /(?<=\()(.*?)(?=\))/
            if (m1) {
                GEWT_PULL_ID = "${m1[0][1]}"
                GEWT_PULL_ID = GEWT_PULL_ID.substring(1)
            }
            m1 = null
            echo "commit_msg=${GEWT_COMMIT_MSG}"
            echo "author=${GEWT_AUTHOR}"
            echo "author_email=${GEWT_AUTHOR_EMAIL}"
            echo "pull_id=${GEWT_PULL_ID}"
        } else {
            echo 'trigger manually'
            echo "param ref not exist"
        }

        if ( env.PD_COMMIT_ID == '') {
            echo "invalid param PD_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param PD_COMMIT_ID')
        }

        echo "COMMIT=${PD_COMMIT_ID}"
        echo "BRANCH=${PD_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "pd_integration_test_ci"),
                booleanParam(name: 'release_test', value: true),
                string(name: 'release_test__release_branch', value: PD_BRANCH),
                string(name: 'release_test__pd_commit', value: PD_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    try {
        stage("Build") {
            build(job: "pd_ghpr_build", parameters: default_params, wait: true)
        }
        stage("Trigger Test Job") {
            container("golang") {
                parallel(
                        // integration test
                        pd_ghpr_integration_compatibility_test: {
                            build(job: "pd_ghpr_integration_compatibility_test", parameters: default_params, wait: true)
                        },
                        pd_ghpr_intergration_common_test: {
                            build(job: "pd_ghpr_intergration_common_test", parameters: default_params, wait: true)
                        },
                        pd_ghpr_intergration_ddl_test: {
                            build(job: "pd_ghpr_intergration_ddl_test", parameters: default_params, wait: true)
                        },
                        pd_ghpr_integration_br_test: {
                            build(job: "pd_ghpr_integration_br_test", parameters: default_params, wait: true)
                        },
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
export BRANCH=${PD_BRANCH}
export COMMIT_ID=${PD_COMMIT_ID}
export AUTHOR=${GEWT_AUTHOR}
export AUTHOR_EMAIL=${GEWT_AUTHOR_EMAIL}
export PULL_ID=${GEWT_PULL_ID}
export GITHUB_OWNER=tikv
export GITHUB_REPO=pd
EOF
                """
                }  else {
                    sh """
                cat > env_param.conf <<EOF
export BRANCH=${PD_BRANCH}
export COMMIT_ID=${PD_COMMIT_ID}
export GITHUB_OWNER=tikv
export GITHUB_REPO=pd
EOF
                """
                }
                sh "cat env_param.conf"
                sh "curl -LO ${FILE_SERVER_URL}/download/cicd/scripts/integration_test_ci_alert.py"

                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'GITHUB_API_TOKEN'),
                                 string(credentialsId: 'feishu-ci-report-integration-test', variable: "FEISHU_ALERT_URL")
                ]) {
                    sh '''#!/bin/bash
                set +x
                export GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
                export FEISHU_ALERT_URL=${FEISHU_ALERT_URL}
                source env_param.conf
                python3 integration_test_ci_alert.py > alert_feishu.log
                set -x
                cat alert_feishu.log
                '''
                }
            }
        }
    }
}
