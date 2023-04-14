// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final GIT_CREDENTIALS_ID = 'gitee-bot-ssh'
final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-enterprise-manager-ui/pod-pr-verify.yaml'
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
        stage("Lint") {
            steps {
                dir(REFS.repo) {
                    script {
                        cache(path: "./node_modules", filter: '**/*', key: prow.getCacheKey('gitee', REFS, 'node_modules'), restoreKeys: prow.getRestoreKeys('gitee', REFS, 'node_modules')) {
                            container('nodejs') {
                                sh script: 'yarn --frozen-lockfile --network-timeout 180000'
                                sh script: 'yarn lint'
                            }
                        }
                    }
                }
            }
        }
        stage("Build") {
            steps {
                container('nodejs') {
                    dir(REFS.repo) {
                        sh script: 'yarn build && tar -zcf dist.tar.gz dist/'
                    }
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        archiveArtifacts(artifacts: 'dist.tar.gz', fingerprint: true, allowEmptyArchive: true)
                    }
                }
            }
        }
    }
    post {
        always {
            addGiteeMRComment("- ${JOB_NAME} `[${currentBuild.result}](${BUILD_URL})`")
        }
    }
}
