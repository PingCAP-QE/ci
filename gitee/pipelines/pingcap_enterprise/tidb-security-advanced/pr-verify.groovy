// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'gitee-bot-ssh'
final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-security-advanced/pod-pr-verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
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
                dir(REFS.repo) {
                    script {
                        cache(path: "./", filter: '**/*', key: prow.getCacheKey('gitee', REFS), restoreKeys: prow.getRestoreKeys('gitee', REFS)) {
                            retry(2) {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout = 5, gitSshHost = 'gitee.com')
                            }
                        }
                    }
                }
            }
        }
        stage("Static-Checks") {
            steps {
                dir(REFS.repo) {
                    echo "WIP"
                }
            }
        }
        stage("Unit-Test") {
            steps {
                dir(REFS.repo) {
                    echo "WIP"
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        // archive test report to Jenkins.
                        junit(testResults: "test.xml", allowEmptyResults: true)
                    }
                }
            }
        }
        stage("Build") {
            steps {
                dir(REFS.repo) {
                    sh script: './build_tidb.sh', label: 'tidb-server'
                    sh script: './build_plugin.sh', label: 'plugins'
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        archiveArtifacts(artifacts: 'bin/*', fingerprint: true, allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
