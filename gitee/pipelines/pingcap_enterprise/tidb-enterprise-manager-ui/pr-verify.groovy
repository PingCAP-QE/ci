// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final POD_TEMPLATE_FILE = 'gitee/pipelines/pingcap_enterprise/tidb-enterprise-manager-ui/pod-pr-verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'nodejs'
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
                                prow.checkoutRefs(REFS, 5, '', 'https://gitee.com')
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
                            sh script: 'yarn --frozen-lockfile --network-timeout 180000'
                            sh script: 'yarn bootstrap'
                            sh script: 'yarn generate'
                            sh script: 'yarn lint:ci'
                        }
                    }
                }
            }
        }        
        stage("Build") {
            steps {
                dir(REFS.repo) {
                    sh script: 'yarn build && tar -zcf dist.tar.gz dist/'
                }
            }
            post {
                success {                                   
                    archiveArtifacts(artifacts: 'dist.tar.gz, vite.config.preview.ts', fingerprint: true, allowEmptyArchive: true)
                }
            }
        }
    }
}
