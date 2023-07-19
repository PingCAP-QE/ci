// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-canary-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
        CI = "1"
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            git.setSshKey(GIT_CREDENTIALS_ID)
                            retry(2) {
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                    container('golang') {
                        sh 'git config --global --add safe.directory `pwd`'
                    }
                }
            }
        }
        stage('Test') {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        sh 'make bazel_coverage_test'
                    }
                }
            }
        }
    }
}
