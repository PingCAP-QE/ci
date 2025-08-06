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
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
                    sh """
                        sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                        git diff .
                        git status
                    """
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
                    sh label: 'Send event to cloudevents server', script: """timeout 10 \
                        curl --verbose --request POST --url http://cloudevents-server.apps.svc/events \
                        --header "ce-id: \$(uuidgen)" \
                        --header "ce-source: \${JENKINS_URL}" \
                        --header 'ce-type: test-case-run-report' \
                        --header 'ce-repo: ${REFS.org}/${REFS.repo}' \
                        --header 'ce-branch: ${REFS.base_ref}' \
                        --header "ce-buildurl: \${BUILD_URL}" \
                        --header 'ce-specversion: 1.0' \
                        --header 'content-type: application/json; charset=UTF-8' \
                        --data @bazel-go-test-problem-cases.json || true
                    """
                    archiveArtifacts(artifacts: 'bazel-*.log, bazel-*.json', fingerprint: false, allowEmptyArchive: true)
                }
            }
        }
        stage('Test Enterprise Extensions') {
            when {
                // Only run the tests when there are changes in the `pkg/extension` folder.
                expression {
                    def changesInExtensionDir = false
                    dir(REFS.repo) {
                        changesInExtensionDir = sh(script: "git diff --name-only ${REFS.base_sha} HEAD | grep -qE '^pkg/extension/'", returnStatus: true) == 0
                    }
                    return changesInExtensionDir
                }
            }
            environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir(REFS.repo) {
                    sh(
                        label: 'test enterprise extensions',
                        script: 'go test --tags intest -coverprofile=coverage-extension.dat -covermode=atomic ./pkg/extension/enterprise/...'
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
    post {
        success {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                sh label: 'Fail when long time cost test cases are found', script: '''#! /usr/bin/env bash

                    threshold=144 # unit is second, we should update it monthly.

                    breakCaseListfile="break_longtime_case.txt"
                    jq -r  ".[] | select(.long_time != null) | .long_time | to_entries[] | select(.value > $threshold) | .key" bazel-go-test-problem-cases.json > $breakCaseListfile

                    if (($(cat $breakCaseListfile | wc -l) > 0)); then
                        echo "$(tput setaf 1)The execution time of these test cases exceeds the threshold($threshold):$(tput sgr0)"
                        cat $breakCaseListfile
                        echo "📌 ref: https://github.com/pingcap/tidb/issues/46820"

                        exit 1
                    fi
                '''
            }
        }
    }
}
