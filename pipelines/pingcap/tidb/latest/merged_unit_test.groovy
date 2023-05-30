// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_unit_test.yaml'
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
        timeout(time: 90, unit: 'MINUTES')
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/${REFS.org}/${REFS.repo}/rev-${REFS.base_sha}", restoreKeys: ["git/${REFS.org}/${REFS.repo}/rev-"]) {
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
            options { timeout(time: 90, unit: 'MINUTES') }
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
                        wget -q -O codecov ${FILE_SERVER_URL}/download/cicd/tools/codecov-v0.5.0
                        chmod +x codecov
                        ./codecov --flags unit --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN} -B ${REFS.base_ref} -C ${REFS.base_sha}
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
}
