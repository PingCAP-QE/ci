
node("${GO_TEST_SLAVE}") {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('refs/heads/') ? it - 'refs/heads/' : it
        }

        if ( env.REF != '' ) {
            echo 'trigger by remote invoke'
            TIDB_BRANCH = trimPrefix(ref)
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
    stage("Build") {
        build(job: "tidb_ghpr_build", parameters: default_params, wait: true)
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
}
