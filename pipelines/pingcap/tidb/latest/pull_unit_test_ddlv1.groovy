// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            retries 2
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '200Gi', storageClassName: 'ci-rwo')
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
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
        stage('Test') {
            environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir(REFS.repo) {
                    script { bazel.prepareWorkspace(repositoryCache: '/share/.cache/bazel-repository-cache', ensureTmpDir: true) }

                    sh '''#!/usr/bin/env bash
                        set -euxo pipefail
                        git diff .
                        git status
                    '''
                    sh '''#! /usr/bin/env bash
                        set -o pipefail

                        ./build/jenkins_unit_test_ddlargsv1.sh 2>&1 | tee bazel-test.log
                    '''
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                        archiveArtifacts(artifacts: 'bazel-test.log', fingerprint: false, allowEmptyArchive: true)
                    }
                    sh label: "Parse flaky test case results", script: './scripts/plugins/analyze-go-test-from-bazel-output.sh tidb/bazel-test.log || true'
                    script {
                        prow.sendTestCaseRunReport("${REFS.org}/${REFS.repo}", "${REFS.base_ref}")
                    }
                    archiveArtifacts(artifacts: 'bazel-*.log, bazel-*.json', fingerprint: false, allowEmptyArchive: true)
                }
            }
        }
    }
}
