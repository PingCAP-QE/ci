// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-qe/tidb-test'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final TARGET_BRANCH_PD = "master"
final TARGET_BRANCH_TIKV = "dedicated"

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        NEXT_GEN = '1'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, trunkBranch=REFS.base_ref, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                }
                dir(REFS.repo) {
                    // Prepare component binaries.
                    dir('bin') {
                        container("utils") {
                            sh """
                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} --pd=${TARGET_BRANCH_PD}-next-gen --tikv=${TARGET_BRANCH_TIKV}-next-gen --tikv-worker=${TARGET_BRANCH_TIKV}-next-gen
                            """
                            sh "cp ${WORKSPACE}/tidb/bin/* ./"
                        }
                    }
                    // Cache for next test stages.
                    cache(path: './', includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: 'cache tidb-test', script: 'touch ws-${BUILD_TAG}'
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PARAMS'
                        values(
                            'jdbc_test ./test_fast.sh',
                            'jdbc_test ./test_slow.sh',
                            'jdbc8_test ./test_fast.sh',
                            'jdbc8_test ./test_slow.sh',
                            'tidb_jdbc_test/tidb_jdbc_unique_test ./test.sh',
                            'tidb_jdbc_test/tidb_jdbc8_test ./test_fast.sh',
                            'tidb_jdbc_test/tidb_jdbc8_test ./test_slow.sh',
                            'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_slow.sh',
                            'tidb_jdbc_test/tidb_jdbc8_tls_test ./test_tls.sh',
                            'mybatis_test ./test.sh',
                            'jooq_test ./test.sh',
                            'hibernate_test/hibernate-orm-test ./test.sh'
                        )
                    }
                    axis {
                        name 'STORE'
                        values "tikv", "unistore"
                    }
                }
                excludes {
                    exclude {
                        axis {
                            name 'TEST_PARAMS'
                            values 'jdbc_test ./test_fast.sh', 'jdbc_test ./test_slow.sh'
                        }
                        axis {
                            name 'STORE'
                            values 'tikv'
                        }
                    }
                    exclude {
                        axis {
                            name 'TEST_PARAMS'
                            notValues 'jdbc_test ./test_fast.sh', 'jdbc_test ./test_slow.sh'
                        }
                        axis {
                            name 'STORE'
                            values 'unistore'
                        }
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'java'
                    }
                }
                stages {
                    stage('Test') {
                        steps {
                            dir(REFS.repo) {
                                // restore the cache saved by previous stage.
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    // if cache missed, it will fail(should not miss).
                                    sh 'chmod +x bin/{tidb-server,pd-server,tikv-server,tikv-worker}'
                                }
                                // run the test.
                                sh label: "store=${STORE} test_params=${TEST_PARAMS} ", script: """#!/usr/bin/env bash
                                    params_array=(\${TEST_PARAMS})
                                    TEST_DIR=\${params_array[0]}
                                    TEST_SCRIPT=\${params_array[1]}
                                    echo "TEST_DIR=\${TEST_DIR}"
                                    echo "TEST_SCRIPT=\${TEST_SCRIPT}"
                                    if [ "$STORE" == "tikv" ]; then
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                        export TIKV_PATH="127.0.0.1:2379"
                                    fi
                                    pushd \${TEST_DIR}
                                        chmod +x *.sh
                                        TIDB_SERVER_PATH=../bin/tidb-server TIDB_TEST_STORE_NAME="${STORE}" ./test.sh 1 \${TEST_SCRIPT}
                                    popd
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
