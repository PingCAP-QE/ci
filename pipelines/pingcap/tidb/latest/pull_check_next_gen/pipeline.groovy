// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = 'jenkins-tidb'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        NEXT_GEN = '1'
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
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID, withSubmodule = true)
                    }
                }
            }
        }
        stage("Checks") {
            environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
            // !!! concurrent go builds will encounter conflicts probabilistically.
            steps {
                dir(REFS.repo) {
                    // sh script: 'make gogenerate check integrationtest'
                    sh script: 'make integrationtest'
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        script {
                            prow.uploadCoverageToCodecov(REFS, 'integration', './coverage.dat')
                        }
                    }
                }
                unsuccessful {
                    dir(REFS.repo) {
                        sh label: "archive log", script: """
                        logs_dir='test_logs'
                        mkdir -p \${logs_dir}
                        mv tests/integrationtest/integration-test.out \${logs_dir} || true
                        tar -czvf \${logs_dir}.tar.gz \${logs_dir} || true
                        """
                        archiveArtifacts(artifacts: '*.tar.gz', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
