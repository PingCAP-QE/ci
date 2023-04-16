// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'gitee-bot-ssh'
final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-enterprise-manager/pod-pr-verify.yaml'
final REFS = gitee.composeRefFromEventPayload(env.jsonBody)

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
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
                container('golang') {
                    dir(REFS.repo) {
                        sh script: 'make lint'
                        sh script: 'gocyclo -over 20 -avg ./ | tee repo_cyclo.log || true'
                    }
                }
            }
        }
        stage("Unit-Test") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        sh script: 'make ci_test'
                    }
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
                container('golang') {
                    dir(REFS.repo) {
                        sh script: 'make build'
                    }
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
    post {
        always {
            addGiteeMRComment(comment: "- ${JOB_NAME} `[${currentBuild.result}](${BUILD_URL})`")
        }
    }
}
