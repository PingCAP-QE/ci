// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_lightning_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            options { timeout(time:10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", includes: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.base_sha}") {
                        sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                    }
                    dir('bin') {
                        container('utils') {
                            sh label: 'download binary', script: """
                                script="${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \$script --pd=${REFS.base_ref} --tikv=${REFS.base_ref} --tiflash=${REFS.base_ref}
                            """
                        }
                    }
                    sh label: "check all tests added to group", script: """#!/usr/bin/env bash
                            chmod +x lightning/tests/*.sh
                            ./lightning/tests/run_group_lightning_tests.sh others
                        """
                    sh '[ -f ./bin/tidb-lightning.test ] || make build_for_lightning_integration_test'
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/lightning-tests") {
                        sh label: 'cache tidb-test', script: """
                            touch ws-${BUILD_TAG}
                            ls -alh ./bin
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/lightning-tests") {
                                    sh label: "TEST_GROUP ${TEST_GROUP}", script: """#!/usr/bin/env bash
                                        chmod +x lightning/tests/*.sh
                                        ./lightning/tests/run_group_lightning_tests.sh ${TEST_GROUP}
                                    """
                                }
                            }

                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """#!/usr/bin/env bash
                                    ls /tmp/lightning_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/lightning_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }
}
