// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.2/pod-ghpr_check.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            retries 2
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'ci-rwo')
            defaultContainer 'golang'
        }
    }
    environment {
        CI = "1"
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
        stage('Prepare bazel workspace') {
            steps {
                dir(REFS.repo) {
                    script { bazel.prepareWorkspace() }
                }
            }
        }
        stage("Checks") {
            steps {
                dir(REFS.repo) {
                    sh script: 'make gogenerate check explaintest'
                }
            }
        }
    }
}
