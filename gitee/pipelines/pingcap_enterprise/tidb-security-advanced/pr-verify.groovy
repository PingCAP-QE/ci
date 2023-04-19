// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'gitee-bot-ssh'
final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-security-advanced/pod-pr-verify.yaml'
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
                addGiteeMRComment(comment: "- :fa-heart: ${JOB_NAME} [started...](${BUILD_URL})")
                dir(REFS.repo) {
                    script {
                        cache(path: "./", filter: '**/*', key: prow.getCacheKey('gitee', REFS), restoreKeys: prow.getRestoreKeys('gitee', REFS)) {
                            retry(2) {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout = 5, gitSshHost = 'gitee.com')
                            }
                        }
                    }
                }
                // the make task will using git cli.
                container('golang') {
                    script {
                        git.setSshKey(GIT_CREDENTIALS_ID, 'gitee.com')
                    }
                }
            }
        }
        stage("Static-Checks") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        echo "WIP"
                    }
                }
            }
        }
        stage("Unit-Test") {
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        echo "WIP"
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
                        sh script: './build_tidb.sh', label: 'tidb-server'
                        sh script: './build_plugin.sh', label: 'plugins'
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
            addGiteeMRComment(comment: "-  :fa-file-o: ${JOB_NAME} [${currentBuild.result}](${BUILD_URL})")
        }
    }
}
