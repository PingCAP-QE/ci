properties([
    parameters([
        string(name: 'URL', description: '', trim: true),
        string(name: 'VERSION', description: '', trim: true),
        booleanParam(name: 'TEST_TIFLASH', defaultValue: false, description: ''),
    ])
])

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

    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-qa"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'toolkit', alwaysPullImage: true,
                        image: "hub.pingcap.net/qa/ci-toolkit:0.9.0", ttyEnabled: true,
                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
    ) {
        node(label) {
        stage("Prepare") {
            container('toolkit') {
                if (release_info.release_branch != "release-3.0") {
                    sh "inv upload --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${params.VERSION}/${release_info.tidb_commit}/centos7/tidb-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${params.VERSION}/${release_info.tidb_commit}/centos7/tidb-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-check/pr/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/pd-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${params.VERSION}/${release_info.pd_commit}/centos7/pd-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/pd/pr/${release_info.pd_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/tidb-lightning.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb-lightning/optimization/${params.VERSION}/${release_info.lightning_commit}/centos7/br-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb-lightning/pr/${release_info.lightning_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/tikv/pr/${release_info.tikv_commit}/centos7/tikv-server.tar.gz  --remote  ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${params.VERSION}/${release_info.tikv_commit}/centos7/tikv-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tikv/pr/${release_info.tikv_commit}/centos7/done --content done"


//                一些集成测试需要集群环境测试，依赖的其他组件有通过 refs/pingcap/xx/${branch}/sha1 找 ref 的逻辑，在这里兼容上传
                    sh "inv upload --dst refs/pingcap/tidb/${release_info.tidb_commit}/sha1 --content ${release_info.tidb_commit}"
                    sh "inv upload --dst refs/pingcap/tikv/${release_info.tikv_commit}/sha1 --content ${release_info.tikv_commit}"
                    sh "inv upload --dst refs/pingcap/pd/${release_info.pd_commit}/sha1 --content ${release_info.pd_commit}"
                    // 上传集成测试流水线需要的产物路径
                    sh "inv upload --dst builds/pingcap/tidb/${release_info.tidb_commit}/${release_info.tidb_commit}/centos7/tidb-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/tidb/optimization/${params.VERSION}/${release_info.tidb_commit}/centos7/tidb-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tidb/${release_info.tidb_commit}/${release_info.tidb_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/pd/${release_info.pd_commit}/${release_info.pd_commit}/centos7/pd-server.tar.gz --remote ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${params.VERSION}/${release_info.pd_commit}/centos7/pd-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/pd/${release_info.pd_commit}/${release_info.pd_commit}/centos7/done --content done"
                    sh "inv upload --dst builds/pingcap/tikv/${release_info.tikv_commit}/${release_info.tikv_commit}/centos7/tikv-server.tar.gz --remote  ${FILE_SERVER_URL}/download/builds/pingcap/tikv/optimization/${params.VERSION}/${release_info.tikv_commit}/centos7/tikv-linux-amd64.tar.gz"
                    sh "inv upload --dst builds/pingcap/tikv/${release_info.tikv_commit}/${release_info.tikv_commit}/centos7/done --content done"


                    if (release_info.containsKey("tiflash_commit") && release_info.tiflash_commit != "") {
                        sh "inv upload --dst refs/pingcap/tiflash/${release_info.tiflash_commit}/sha1 --content ${release_info.tiflash_commit}"
                        sh "inv upload --dst refs/pingcap/ticdc/${release_info.release_branch}/sha1 --content ${release_info.ticdc_commit}"
                    }
                    if (release_info.containsKey("br_commit") && release_info.br_commit != "") {
                        sh "inv upload --dst refs/pingcap/br/${release_info.br_commit}/sha1 --content ${release_info.br_commit}"
                    }
                    if (release_info.containsKey("ticdc_commit") && release_info.ticdc_commit != "") {
                        sh "inv upload --dst refs/pingcap/tiflow/${release_info.ticdc_commit}/sha1 --content ${release_info.ticdc_commit}"
                    }
                    if (release_info.containsKey("dm_commit") && release_info.dm_commit != "") {
                        sh "inv upload --dst refs/pingcap/tiflow/${release_info.dm_commit}/sha1 --content ${release_info.dm_commit}"
                    }
                    for (int i = 0; i < release_info.tidb_old_commits.size(); i++) {
                        sh "inv upload --dst builds/download/refs/pingcap/tidb/${release_info.tidb_old_commits[i]}/sha1 --content ${release_info.tidb_old_commits[i]}"
                    }
                } else {
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
string(name: 'release_test__cdc_commit', value: release_info.getOrDefault('ticdc_commit', '')),
string(name: 'release_test__dm_commit', value: release_info.getOrDefault('dm_commit', ''))
            ]
            echo("default params: ${default_params}")
            parallel(
                    Group1: {
                        build(job: "tidb_ghpr_common_test", parameters: default_params)
                        build(job: "tidb_ghpr_integration_common_test", parameters: default_params)
                    },
                    Group2: {
                        build(job: "tidb_ghpr_sqllogic_test_1", parameters: default_params)
                        build(job: "tidb_ghpr_sqllogic_test_2", parameters: default_params)
                        build(job: "tidb_ghpr_integration_ddl_test", parameters: default_params)
                    },
                    Group3: {
                        if (release_info.br_commit) {
                            build(job: "br_ghpr_unit_and_integration_test", parameters: default_params)
                        }
                    },
                    Group4: {
                        if (release_info.binlog_commit) {
                            build(job: "binlog_ghpr_integration", parameters: default_params)
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
                            build(job: "tiflash-ghpr-build", parameters: params)
                            build(job: "tiflash-ghpr-integration-tests", parameters: params)
                        }
                    },
                    Group6: {
                        if (release_info.ticdc_commit) {
                            build(job: "cdc_ghpr_integration_test", parameters: default_params)
                            build(job: "cdc_ghpr_kafka_integration_test", parameters: default_params)
                        }
                    },
                    Group7: {
                        if (release_info.tools_commit) {
                            build(job: "tools_ghpr_integration", parameters: default_params)
                        }
                    },
                    Group8: {
                        if (release_info.dm_commit) {
                            build(job: "dm_ghpr_integration_test", parameters: default_params)
                            build(job: "dm_ghpr_compatibility_test", parameters: default_params)
                        }
                    },
                    Group9: { // TiKV
                            build(job: "tikv_ghpr_integration-copr-test", parameters: default_params)
                            build(job: "tikv_ghpr_integration_compatibility_test", parameters: default_params)
                    }
            )
        }
    }
}
}
