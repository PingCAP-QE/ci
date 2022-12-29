// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.3/pod-ghpr_unit_test.yaml'
final REFS = prow.getJobRefs(params.PROW_DECK_URL, params.PROW_JOB_ID)

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
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                prow.checkoutPr(params.PROW_DECK_URL, params.PROW_JOB_ID)
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
                        wget -q -O codecov ${FILE_SERVER_URL}/download/cicd/tools/codecov-v0.3.2
                        chmod +x codecov
                        ./codecov --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN}
                        """
                    }
                }
                always {
                    dir('tidb') {
                        // archive test report to Jenkins.
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)

                        // upload coverage report to file server
                        retry(3) {
                            sh label: "upload coverage report to ${FILE_SERVER_URL}", script: """
                                filepath="tipipeline/test/report/\${JOB_NAME}/\${BUILD_NUMBER}/${REFS.pulls[0].sha}/report.xml"
                                curl -f -F \${filepath}=@test_coverage/bazel.xml \${FILE_SERVER_URL}/upload
                                echo "coverage download link: \${FILE_SERVER_URL}/download/\${filepath}"
                                """
                        }
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
                    junitUrl="\${FILE_SERVER_URL}/download/tipipeline/test/report/\${JOB_NAME}/\${BUILD_NUMBER}/${REFS.pulls[0].sha}/report.xml"
                    bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json "\${junitUrl}" || true
                """
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
