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
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '200Gi', storageClassName: 'hyperdisk-rwo')
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
                    sh '''#!/usr/bin/env bash
                        set -euxo pipefail

                        # Clean legacy cache/mirror URLs that are unstable on GCP workers.
                        for file in WORKSPACE DEPS.bzl; do
                            [ -f "$file" ] || continue
                            sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$file"
                        done
                        echo "removed legacy bazel mirrors from WORKSPACE/DEPS.bzl for gcp replay"

                        # Avoid "check" targets re-writing legacy cache settings during migration replay.
                        sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true

                        # Ensure expected bazel tmp dir exists after mount point change.
                        mkdir -p /home/jenkins/.tidb/tmp

                        # Prefer shared local repository cache when writable, fallback to default path.
                        if [ -d /share/.cache/bazel-repository-cache ] && mkdir -p /share/.cache/bazel-repository-cache/content_addressable/sha256 2>/dev/null; then
                            sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                            echo "using shared bazel repository cache: /share/.cache/bazel-repository-cache"
                        else
                            echo "shared bazel repository cache unavailable or not writable, keep repository_cache=/home/jenkins/.tidb/tmp"
                        fi

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
