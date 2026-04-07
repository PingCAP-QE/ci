// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.5-with-kv-timeout-feature/pod-ghpr_check.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final GIT_CREDENTIALS_ID = ''

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            steps {
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS, credentialsId = GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage("Checks") {
            // !!! concurrent go builds will encounter conflicts probabilistically.
            steps {
                dir('tidb') {
                    sh label: 'fix bazel', script: 'rm -rf /home/jenkins/.cache/bazel/_bazel_jenkins/install/a09dbb90c658248f08f9aa0eba11997d'
                    sh script: 'make gogenerate check explaintest'
                }
            }
        }
    }
}
