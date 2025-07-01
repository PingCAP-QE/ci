// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-8.5/pod-ghpr_verify.yaml'
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
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
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
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMD'
                        values 'check', "build", "dm_unit_test_in_verify_ci", "unit_test_in_verify_ci", "engine_unit_test_in_verify_ci"
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
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment {
                            CODECOV_TOKEN = credentials('codecov-token-tiflow')
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    sh label: "${TEST_CMD}", script: """
                                        make ${TEST_CMD}
                                    """
                                }
                            }
                        }
                        post {
                            success {
                                dir('tiflow') {
                                    script {
                                        def testConfigs = [
                                            dm_unit_test_in_verify_ci: [flags: "unit", coverage_file: "/tmp/dm_test/cov.unit_test.out"],
                                            unit_test_in_verify_ci: [flags: "unit", coverage_file: "/tmp/tidb_cdc_test/cov.unit.out"],
                                            engine_unit_test_in_verify_ci: [flags: "unit", coverage_file: "/tmp/engine_test/cov.unit_test.out"]
                                        ]
                                        def config = testConfigs[TEST_CMD]
                                        if (config && config.coverage_file) {
                                            prow.uploadCoverageToCodecov(REFS, config.flags, config.coverage_file)
                                        }
                                    }
                                }
                            }
                            always {
                                junit(testResults: "**/tiflow/*-junit-report.xml", allowEmptyResults : true)
                            }
                        }
                    }
                }
            }
        }
    }
}
