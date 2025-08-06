// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-canary-ghpr_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
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
            steps {
                container('golang') {
                    dir(REFS.repo) {
                        sh """#!/usr/bin/env bash

                            # use emptydir volume
                            sed -i 's|--output_user_root=/home/jenkins/.tidb/tmp|--output_user_root=/home/jenkins/agent/workspace/.tidb/tmp|g' Makefile.common
                            sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                            git diff .
                            git status
                        """
                        sh 'make bazel_coverage_test'
                    }
                }
            }
        }
    }
}
