// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
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
        NEXT_GEN = '1'
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
                    script {
                        prow.setPRDescription(REFS)
                    }
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
                    sh """#! /usr/bin/env bash
                        set -o pipefail
                        make bazel_coverage_test_ddlargsv1
                    """
                }
            }
        }
    }
}
