
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
            echo "invalid param CDC_COMMIT_ID"
            currentBuild.result = "FAILURE"
            error('Stopping early… invalid param CDC_COMMIT_ID')
        }

        echo "COMMIT=${TIDB_COMMIT_ID}"
        echo "BRANCH=${TIDB_BRANCH}"

        default_params = [
                string(name: 'triggered_by_upstream_ci', value: "cdc_integration_test_ci"),
                booleanParam(name: 'release_test', value: true),
                string(name: 'release_test__release_branch', value: CDC_BRANCH),
                string(name: 'release_test__cdc_commit', value: CDC_COMMIT_ID),
        ]

        echo("default params: ${default_params}")

    }

    stage("Trigger Test Job") {
        container("golang") {
            parallel(
                    // integration test
                    cdc_ghpr_test: {
                        build(job: "cdc_ghpr_test", parameters: default_params, wait: true)
                    },
                    cdc_ghpr_leak_test: {
                        build(job: "cdc_ghpr_test", parameters: default_params, wait: true)
                    },
                    cdc_ghpr_integration_test: {
                        build(job: "cdc_ghpr_test", parameters: default_params, wait: true)
                    },
                    cdc_ghpr_kafka_integration_tes: {
                        build(job: "cdc_ghpr_test", parameters: default_params, wait: true)
                    },
            )
        }
    }
}
