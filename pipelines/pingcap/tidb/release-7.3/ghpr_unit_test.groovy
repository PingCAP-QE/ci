// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-7.3/pod-ghpr_unit_test.yaml'
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
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
        CI = "1"
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            git.setSshKey(GIT_CREDENTIALS_ID)
                            retry(2) {
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                }
            }
        }
        stage('Test') {
            environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir(REFS.repo) {
                    sh '''#! /usr/bin/env bash
                        set -o pipefail

                        ./build/jenkins_unit_test.sh 2>&1 | tee bazel-test.log
                    '''
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        script {
                            prow.uploadCoverageToCodecov(REFS, 'unit', './coverage.dat')
                        }
                    }
                }
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
        stage('Test Enterprise Extensions') {
            when {
                expression {
                    // Q: why this step is not existed in presubmit job of master branch?
                    // A: we should not forbiden the community contrubutor on the unit test on private submodules.
                    // if it failed, the enterprise extension owners should fix it.
                    return REFS.base_ref != 'master' || REFS.pulls == null || REFS.pulls.size() == 0
                }
            }
            environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir(REFS.repo) {
                    sh(
                        label: 'test enterprise extensions',
                        script: 'go test --tags intest -coverprofile=coverage-extension.dat -covermode=atomic ./extension/enterprise/...'
                    )
                }
            }
            post {
                success {
                    dir(REFS.repo) {
                        script {
                            prow.uploadCoverageToCodecov(REFS, 'unit', './coverage-extension.dat')
                        }
                    }
                }
            }
        }
    }
}
