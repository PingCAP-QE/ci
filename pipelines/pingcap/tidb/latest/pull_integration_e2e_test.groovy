// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_e2e_test.yaml'
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
        stage('Prepare') {
            steps {
                dir('tidb') {
                    script {
                        // Computes the branch name for downloading binaries based on the PR target branch and title.
                        def otherComponentBranch = component.computeBranchFromPR('other', REFS.base_ref, REFS.pulls[0].title, 'master')
                        retry(3) {
                            sh label: 'download binary', script: """
                                cd tests/integrationtest2 && ./download_integration_test_binaries.sh ${otherComponentBranch}
                                ls -alh third_bin/
                                ./third_bin/tikv-server -V
                                ./third_bin/pd-server -V
                                ./third_bin/tiflash --version
                                ./third_bin/cdc version
                            """
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'test', script: """
                        cd tests/integrationtest2 && ./run-tests.sh
                    """
                }
            }
            post{
                failure {
                    script {
                        archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
