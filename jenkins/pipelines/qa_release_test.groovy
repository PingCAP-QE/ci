// properties([
//     parameters([
//         string(name: 'URL', description: '', trim: true),
//         booleanParam(name: 'TEST_TIFLASH', defaultValue: false, description: ''),
//     ])
// ])

catchError {
    def response
    def release_info
    node {
        echo params.URL
        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
            response = sh returnStdout: true, script: "curl -L -u sre-bot:${TOKEN} --silent ${params.URL}"
            //response = sh returnStdout: true, script: "curl -L -u sre-bot:${TOKEN} --silent 'http://fileserver.pingcap.net/download/pingcap/qa/draft/release-test.json'"
        }
        echo response
        release_info = readJSON text: response
    }
    node("toolkit") {
        stage("Prepare") {
            container('toolkit') {
                if (release_info.release_branch != "release-3.0") {
                    sh "inv upload --force --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${release_info.tidb_commit}/centos7/tidb-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${release_info.tidb_commit}/centos7/tidb-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/pd-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${release_info.pd_commit}/centos7/pd-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/tidb-lightning.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb-lightning/optimization/${release_info.lightning_commit}/centos7/tidb-lightning.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/tikv/${release_info.tikv_commit}/centos7/tikv-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${release_info.tikv_commit}/centos7/tikv-server.tar.gz"

//                一些集成测试依赖其他组件，同时 e2e 测试需求，需要进行二进制替换
//                    sh "inv upload --force --dst builds/pingcap/tikv/${release_info.tikv_commit}/centos7/tikv-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${release_info.tikv_commit}/centos7/tikv-server.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/tidb/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${release_info.tidb_commit}/centos7/tidb-server.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/pd/${release_info.pd_commit}/centos7/pd-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${release_info.pd_commit}/centos7/pd-server.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/importer/${release_info.importer_commit}/centos7/importer.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${release_info.importer_commit}/centos7/importer.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/ticdc/${release_info.ticdc_commit}/centos7/ticdc.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/optimization/${release_info.ticdc_commit}/centos7/ticdc.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/tidb-binlog/${release_info.binlog_commit}/centos7/tidb-binlog.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${release_info.binlog_commit}/centos7/tidb-binlog.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/tidb-tools/${release_info.tools_commit}/centos7/tidb-tools.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${release_info.tools_commit}/centos7/tidb-tools.tar.gz"

//                    tiflash 和 br 在 cd 上传的时候为了兼容，两种路径都上传
//                    sh "inv upload --force --dst builds/pingcap/tiflash/${release_info.release_branch}/${release_info.tiflash_commit}/centos7/tiflash.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${release_info.tiflash_commit}/centos7/tiflash.tar.gz"
//                    sh "inv upload --force --dst builds/pingcap/br/${release_info.release_branch}/${release_info.br_commit}/centos7/br.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${release_info.br_commit}/centos7/br.tar.gz"

//                一些集成测试需要集群环境测试，依赖的其他组件有通过 refs/pingcap/xx/${branch}/sha1 找 ref 的逻辑，在这里兼容上传
                    sh "inv upload --dst refs/pingcap/tidb/${release_info.tidb_commit}/sha1 --content ${release_info.tidb_commit}"
                    sh "inv upload --dst refs/pingcap/tikv/${release_info.tikv_commit}/sha1 --content ${release_info.tikv_commit}"
                    sh "inv upload --dst refs/pingcap/pd/${release_info.pd_commit}/sha1 --content ${release_info.pd_commit}"

                    if (release_info.containsKey("tiflash_commit") && release_info.tiflash_commit != "") {
                        sh "inv upload --dst refs/pingcap/tiflash/${release_info.tiflash_commit}/sha1 --content ${release_info.tiflash_commit}"
                    }
                    if (release_info.containsKey("br_commit") && release_info.br_commit != "") {
                        sh "inv upload --dst refs/pingcap/br/${release_info.br_commit}/sha1 --content ${release_info.br_commit}"
                    }
                    if (release_info.containsKey("ticdc_commit") && release_info.ticdc_commit != "") {
                        sh "inv upload --dst refs/pingcap/ticdc/${release_info.ticdc_commit}/sha1 --content ${release_info.ticdc_commit}"
                    }
                    for (int i = 0; i < release_info.tidb_old_commits.size(); i++) {
                        sh "inv upload --dst builds/download/refs/pingcap/tidb/${release_info.tidb_old_commits[i]}/sha1 --content ${release_info.tidb_old_commits[i]}"
                    }
                } else {
                    sh "inv upload --force --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${release_info.tidb_commit}/centos7/tidb-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${release_info.tidb_commit}/centos7/tidb-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/pd-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/pd/${release_info.pd_commit}/centos7/pd-server.tar.gz"
                    sh "inv upload --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/done --content done"
                    sh "inv upload --force --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/tidb-lightning.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb-lightning/${release_info.lightning_commit}/centos7/tidb-lightning.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/done --content done"

//                一些集成测试需要集群环境测试，依赖的其他组件有通过 refs/pingcap/xx/${branch}/sha1 找 ref 的逻辑，在这里兼容上传
                    sh "inv upload --dst refs/pingcap/tidb/${release_info.tidb_commit}/sha1 --content ${release_info.tidb_commit}"
                    sh "inv upload --dst refs/pingcap/tikv/${release_info.tikv_commit}/sha1 --content ${release_info.tikv_commit}"
                    sh "inv upload --dst refs/pingcap/pd/${release_info.pd_commit}/sha1 --content ${release_info.pd_commit}"

                    if (release_info.containsKey("tiflash_commit") && release_info.tiflash_commit != "") {
                        sh "inv upload --dst refs/pingcap/tiflash/${release_info.tiflash_commit}/sha1 --content ${release_info.tiflash_commit}"
                    }
                    if (release_info.containsKey("br_commit") && release_info.br_commit != "") {
                        sh "inv upload --dst refs/pingcap/br/${release_info.br_commit}/sha1 --content ${release_info.br_commit}"
                    }
                    if (release_info.containsKey("ticdc_commit") && release_info.ticdc_commit != "") {
                        sh "inv upload --dst refs/pingcap/ticdc/${release_info.ticdc_commit}/sha1 --content ${release_info.ticdc_commit}"
                    }
                    for (int i = 0; i < release_info.tidb_old_commits.size(); i++) {
                        sh "inv upload --dst builds/download/refs/pingcap/tidb/${release_info.tidb_old_commits[i]}/sha1 --content ${release_info.tidb_old_commits[i]}"
                    }
                }
            }
        }
        stage("Test") {
            def default_params = [
//                    一些检测 commit 是否重跑的逻辑，force 不触发该逻辑
booleanParam(name: 'force', value: true),
booleanParam(name: 'release_test', value: true),
string(name: 'release_test__release_branch', value: release_info.release_branch),
string(name: 'release_test__tidb_commit', value: release_info.tidb_commit),
string(name: 'release_test__tikv_commit', value: release_info.tikv_commit),
string(name: 'release_test__pd_commit', value: release_info.pd_commit),
string(name: 'release_test__binlog_commit', value: release_info.getOrDefault('binlog_commit', '')),
string(name: 'release_test__lightning_commit', value: release_info.getOrDefault('lightning_commit', '')),
string(name: 'release_test__importer_commit', value: release_info.getOrDefault('importer_commit', '')),
string(name: 'release_test__tools_commit', value: release_info.getOrDefault('tools_commit', '')),
string(name: 'release_test__tiflash_commit', value: release_info.getOrDefault('tiflash_commit', '')),
string(name: 'release_test__br_commit', value: release_info.getOrDefault('br_commit', '')),
string(name: 'release_test__cdc_commit', value: release_info.getOrDefault('ticdc_commit', ''))
            ]
            echo("default params: ${default_params}")
            parallel(
                    Group1: {
                        for (int i = 0; i < release_info.tidb_old_commits.size(); i++) {
                            container('toolkit') {
                                sh "curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${release_info.tidb_old_commits[i]}/sha1"
                                sh "curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${release_info.tidb_old_commits[i]}/centos7/tidb-server.tar.gz"
                            }
                            this_params = default_params.collect()
                            this_params.add(string(name: 'release_test__tidb_old_commit', value: release_info.tidb_old_commits[i]))
                            build(job: "tidb_ghpr_integration_campatibility_test", parameters: this_params)
                        }
                        build(job: "tidb_ghpr_unit_test", parameters: default_params)
                        build(job: "tidb_ghpr_common_test", parameters: default_params)
                        build(job: "tidb_ghpr_integration_common_test", parameters: default_params)
                    },
                    Group2: {
                        build(job: "tidb_ghpr_sqllogic_test_1", parameters: default_params)
                        build(job: "tidb_ghpr_sqllogic_test_2", parameters: default_params)
                        build(job: "tidb_ghpr_integration_ddl_test", parameters: default_params)
                        build(job: "tidb_ghpr_mybatis", parameters: default_params)
                    },
                    Group3: {
                        if (release_info.lightning_commit) {
                            build(job: "lightning_ghpr_test", parameters: default_params)
                        }
                        if (release_info.importer_commit) {
                            build(job: "importer_ghpr_test", parameters: default_params)
                        }
                        if (release_info.br_commit) {
                            build(job: "br_ghpr_unit_and_integration_test", parameters: default_params)
                        }
                    },
                    Group4: {
                        if (release_info.binlog_commit) {
                            build(job: "binlog_ghpr_integration", parameters: default_params)
                        }
                        if (release_info.tools_commit) {
                            build(job: "tools_ghpr_integration", parameters: default_params)
                        }
                    },
                    Group5: {
                        if (release_info.tiflash_commit && params.TEST_TIFLASH) {
                            def params = [
                                    booleanParam(name: 'force', value: true),
                                    booleanParam(name: 'release_test', value: true),
                                    string(name: 'ghprbTargetBranch', value: release_info.release_branch),
                                    string(name: 'ghprbActualCommit', value: release_info.tiflash_commit),
                                    string(name: 'ghprbPullTitle', value: "QA Release Test"),
                                    string(name: 'ghprbPullDescription', value: "This build is triggered by qa for release testing."),
                            ]
                            build(job: "tics_ghpr_build", parameters: params)
                            build(job: "tics_ghpr_test", parameters: params)
                        }
                    },
                    Group6: {
                        if (release_info.ticdc_commit) {
                            build(job: "cdc_ghpr_integration_test", parameters: default_params)
                            build(job: "cdc_ghpr_kafka_integration_test", parameters: default_params)
                        }
                    },
                    Group7: {
                        build(job: "pd_test", parameters: default_params)
                    },
                    Group8: {
                        def params = [
                                string(name: 'TIDB_BRANCH_OR_COMMIT', value: release_info.tidb_commit),
                                string(name: 'TIKV_BRANCH_OR_COMMIT', value: release_info.tikv_commit),
                                string(name: 'PD_BRANCH_OR_COMMIT', value: release_info.pd_commit),
                                string(name: 'BINLOG_BRANCH_OR_COMMIT', value: release_info.binlog_commit),
                                string(name: 'BR_BRANCH_AND_COMMIT', value: release_info.release_branch + "/" + release_info.br_commit),
                                string(name: 'TICDC_BRANCH_OR_COMMIT', value: release_info.ticdc_commit),
                                string(name: 'TOOLS_BRANCH_OR_COMMIT', value: release_info.tools_commit),
                                string(name: 'TIFLASH_BRANCH_AND_COMMIT', value: release_info.release_branch + "/" + release_info.tiflash_commit),
                        ]
                        if (release_info.release_branch >= "release-4.0") {
                            build(job: "tidb_and_tools_e2e_test", parameters: params)
                        }
                    }
            )
        }
    }
}
