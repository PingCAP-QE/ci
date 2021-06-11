
node("${GO_TEST_SLAVE}") {
    stage("Print env"){
        // commit id / branch / pusher / commit message
        def trimPrefix = {
            it.startsWith('origin/') ? it - 'origin/' : it
        }
        GIT_BRANCH = trimPrefix(ref)
        echo "commit id: ${GIT_COMMIT}"
        echo "branch: ${GIT_BRANCH}"
        echo "push by ${PUSHER}"
        echo "commit message: ${GIT_COMMIT_MSG}"

    }
    stage("Trigger Test Job") {
        container("golang") {
            def default_params = [
                    booleanParam(name: 'release_test', value: true),
                    string(name: 'release_test__release_branch', value: release_info.GIT_BRANCH),
                    string(name: 'release_test__tidb_commit', value: release_info.GIT_COMMIT),
            ]
            echo("default params: ${default_params}")
            parallel(
                    Build1: {
                        build(job: "tidb_ghpr_common_test", parameters: default_params)
                    },
//            Build2: {
//                build(job: "tidb_ghpr_integration_common_test", parameters: default_params)
//            },
//            Build3: {
//                build(job: "tidb_ghpr_integration_campatibility_test", parameters: default_params)
//            },
//            Build4: {
//                build(job: "tidb_ghpr_integration_common_test", parameters: default_params)
//            },
//            Build5: {
//                build(job: "tidb_ghpr_integration_copr_test", parameters: default_params)
//            },
//            Build6: {
//                build(job: "tidb_ghpr_integration_ddl_test", parameters: default_params)
//            },
//            Build8: {
//                build(job: "tidb_ghpr_mybatis", parameters: default_params)
//            },
//            Build9: {
//                build(job: "tidb_ghpr_sqllogic_test_1", parameters: default_params)
//            },
//            Build10: {
//                build(job: "tidb_ghpr_sqllogic_test_2", parameters: default_params)
//            },
//            Build11: {
//                build(job: "tidb_ghpr_unit_test", parameters: default_params)
//            },
            )
        }
    }
}


