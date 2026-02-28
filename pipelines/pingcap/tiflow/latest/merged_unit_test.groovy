// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-merged_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent none
    environment {
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMD'
                        values "dm_unit_test_in_verify_ci", "unit_test_in_verify_ci", "engine_unit_test_in_verify_ci"
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
                        environment {
                            CODECOV_TOKEN = credentials('codecov-token-tiflow')
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                                    retry(2) {
                                        script {
                                            prow.checkoutRefs(REFS)
                                        }
                                    }
                                }
                                sh label: "${TEST_CMD}", script: "make ${TEST_CMD}"
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
                                dir('tiflow') {
                                    container(name: 'codecov') {
                                        sh label: "upload junit report to codecov", script: """
                                        JUNIT_REPORT=\$(ls *-junit-report.xml)
                                        wget -q -O codecovcli https://cli.codecov.io/v0.9.4/linux/codecovcli
                                        chmod +x codecovcli
                                        git config --global --add safe.directory '*'
                                        ./codecovcli do-upload --report-type test_results --file \${JUNIT_REPORT} --branch origin/${REFS.base_ref} --sha ${REFS.base_sha}
                                        """
                                    }

                                }
                                junit(testResults: "**/tiflow/*-junit-report.xml", allowEmptyResults : true)
                            }
                        }
                    }
                }
            }
        }
    }
}
