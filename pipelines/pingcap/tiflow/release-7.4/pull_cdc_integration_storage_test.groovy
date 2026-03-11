// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-7.4/pod-pull_cdc_integration_storage_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
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
        stage("prepare") {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            cd ../tiflow && ./scripts/download-integration-test-binaries.sh release-7.4 && ls -alh ./bin
                            make check_third_party_binary
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiflash --version
                            ./bin/sync_diff_inspector --version
                        """
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-test')) {
                        // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                            [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                            [ -f ./bin/cdc.test ] || make integration_test_build
                            ls -alh ./bin
                            ./bin/cdc version
                        """
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09',
                            'G10', 'G11', 'G12', 'G13', 'G14', 'G15', 'G16', 'G17'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment {
                            TICDC_CODECOV_TOKEN = credentials('codecov-token-tiflow')
                            TICDC_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    sh label: "${TEST_GROUP}", script: """
                                        rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                        chmod +x ./tests/integration_tests/run_group.sh
                                        ./tests/integration_tests/run_group.sh storage ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tidb_cdc_test/
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")
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
