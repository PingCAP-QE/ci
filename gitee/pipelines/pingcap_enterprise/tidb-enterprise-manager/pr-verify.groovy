// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-enterprise-manager/pod-pr-verify.yaml'
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
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage("Static-Checks") {
            steps {
                dir(REFS.repo) {
                    sh script: 'make lint'
                    sh script: 'gocyclo -over 20 -avg ./ | tee repo_cyclo.log || true'                
                }
            }
        }
        stage("Unit-Test") {
            steps {
                dir(REFS.repo) {
                    sh script: 'make ci_test'
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
                    sh script: 'make'
                }
            }
            post {
                success {                    
                    archiveArtifacts(artifacts: 'bin/*', fingerprint: true, allowEmptyArchive: true)
                }
            }
        }
    }
}
