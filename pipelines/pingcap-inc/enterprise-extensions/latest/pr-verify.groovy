// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-inc/enterprise-extensions'
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/enterprise-extensions/latest/pod-pr-verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, '')
                            }
                        }
                    }
                }
                dir('tidb/pkg/extension/enterprise') {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)                                        
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                container('golang') {
                    sh '''
                        git config --global --add safe.directory $(pwd)
                        git config --global --add safe.directory $(pwd)/tidb
                        git config --global --add safe.directory $(pwd)/tidb/pkg/extension/enterprise
                    '''
                }
            }
        }
        stage('Check') {
            steps {
                container('golang') {
                    sh script: 'make gogenerate check integrationtest bazel_lint -C tidb'
                }
            }
        }
        stage("Test") {
            steps {
                container('golang') {
                    dir('tidb') {
                        sh label: 'Unit Test', script: 'go test --tags intest -v ./pkg/extension/enterprise/...'
                    }
                }
            }
        }
        stage("Build") {
            steps {
                container('golang') {
                    // We should not update `extension` dir with `enterprise-server` make task.
                    sh 'make enterprise-prepare enterprise-server-build -C tidb'
                }
            }
            post {
                success {
                    // should not archive it for enterprise edition.
                    echo 'Wont archive artifacts publicly for enterprise building'
                    // archiveArtifacts(artifacts: 'tidb/tidb-server', fingerprint: true, allowEmptyArchive: true)
                }
            }
        }
    }   
}
