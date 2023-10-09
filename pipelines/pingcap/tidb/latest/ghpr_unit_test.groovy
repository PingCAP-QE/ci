// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
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
                        // Fail when found long time test cases.
                    }
                }
                always {
                    dir(REFS.repo) {
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                        archiveArtifacts(artifacts: 'bazel-test.log', fingerprint: false, allowEmptyArchive: true)
                    }

                    sh label: "Parse flaky test case results", script: './scripts/plugins/analyze-go-test-from-bazel-output.sh tidb/bazel-test.log || true'
                    container('deno') {
                        sh label: "Report flaky test case results", script: """
                            deno run --allow-all http://fileserver.pingcap.net/download/ci/scripts/plugins/report-flaky-cases-v20230821.ts \
                                --repo=${REFS.org}/${REFS.repo} \
                                --branch=${REFS.base_ref} \
                                --build_url=\${BUILD_URL} \
                                --caseDataFile=bazel-go-test-problem-cases.json || true
                        """
                    }
                    archiveArtifacts(artifacts: 'bazel-*.log, bazel-*.json', fingerprint: false, allowEmptyArchive: true)
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
