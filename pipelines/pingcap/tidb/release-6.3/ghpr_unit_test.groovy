// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.3/pod-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            steps {
                dir('tidb') {
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
        stage('Test') {
            environment { TIDB_CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir('tidb') {
                    sh './build/jenkins_unit_test.sh'
                }
            }
            post {
                 success {
                    dir("tidb") {
                        sh label: "upload coverage to codecov", script: """
                        mv coverage.dat test_coverage/coverage.dat
                        wget -q -O codecov https://uploader.codecov.io/v0.5.0/linux/codecov
                        chmod +x codecov
                        ./codecov --flags unit --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN} --pr ${REFS.pulls[0].number} --sha ${REFS.pulls[0].sha} --branch origin/pr/${REFS.pulls[0].number}
                        """
                    }
                }
                always {
                    dir('tidb') {
                        // archive test report to Jenkins.
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                    }
                }
            }
        }
    }
    post {
        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {
                sh """
                    junitUrl=""
                    bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json "\${junitUrl}" || true
                """
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
