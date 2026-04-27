// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-9.0-beta/pod-pull_unit_test.yaml'
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
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            retry(2) {
                                prow.checkoutRefs(REFS, credentialsId = GIT_CREDENTIALS_ID, timeout = 5, withSubmodule = true, gitBaseUrl = 'https://github.com')
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
                        sed -i 's|xargs bazel \$(BAZEL_GLOBAL_CONFIG) run \$(BAZEL_CMD_CONFIG) @com_github_pingcap_failpoint//failpoint-ctl:failpoint-ctl -- enable|xargs -n 200 bazel \$(BAZEL_GLOBAL_CONFIG) run \$(BAZEL_CMD_CONFIG) @com_github_pingcap_failpoint//failpoint-ctl:failpoint-ctl -- enable|' Makefile || true
                        sed -i 's|xargs bazel \$(BAZEL_GLOBAL_CONFIG) run \$(BAZEL_CMD_CONFIG) @com_github_pingcap_failpoint//failpoint-ctl:failpoint-ctl -- disable|xargs -n 200 bazel \$(BAZEL_GLOBAL_CONFIG) run \$(BAZEL_CMD_CONFIG) @com_github_pingcap_failpoint//failpoint-ctl:failpoint-ctl -- disable|' Makefile || true
                        sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                        grep -n 'xargs -n 200 bazel .*failpoint-ctl:failpoint-ctl --' Makefile | head -n 4 || true
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
