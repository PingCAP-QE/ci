// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.5/pod-pull_integration_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC = component.computeArtifactOciTagFromPR('ticdc', REFS.base_ref, REFS.pulls[0].title, 'master')

final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID)
                    }
                }
            }
        }
        stage('Hotfix bazel deps URL (temporary)') {
            steps {
                dir(REFS.repo) {
                    sh '''#!/usr/bin/env bash
                    set -euxo pipefail
                    for f in WORKSPACE DEPS.bzl; do
                      [ -f "$f" ] || continue
                      sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$f"
                    done

                    # Keep replay and job behavior aligned until tidb repo deps URLs are cleaned up.
                    sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true

                    grep -nE 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build' WORKSPACE DEPS.bzl || true
                    grep -n '^check:' Makefile | head -n 3 || true
                    '''
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb/tests/integrationtest2/third_bin') {
                    script {
                        retry(3) {
                            container('utils') {
                                sh label: 'download binary', script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                        --pd=${OCI_TAG_PD} \
                                        --tikv=${OCI_TAG_TIKV} \
                                        --tiflash=${OCI_TAG_TIFLASH} \
                                        --ticdc-new=${OCI_TAG_TICDC}
                                """
                            }
                            sh label: 'verify binaries', script: '''
                                ls -alh .
                                ./tikv-server -V
                                ./pd-server -V
                                ./tiflash --version
                                ./cdc version
                            '''
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir('tidb/tests/integrationtest2') {
                    sh label: 'test', script: './run-tests.sh'
                }
            }
            post{
                failure {
                    script {
                        println "Test failed, archive the log"
                        archiveArtifacts artifacts: 'tidb/tests/integrationtest2/logs', fingerprint: true
                    }
                }
            }
        }
    }
}
