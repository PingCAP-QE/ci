// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_lightning_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_FAKE_GCS_SERVER = 'v1.54.0'
final OCI_TAG_KES = 'v0.14.0'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yamlFile POD_TEMPLATE_FILE
                    defaultContainer 'golang'
                }
            }
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                    cache(path: "./bin", includes: 'tidb-server', key: prow.getCacheKey('binary', REFS, 'tidb-server')) {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                    cache(path: "./bin", includes: 'tidb-lightning*', key: prow.getCacheKey('binary', REFS, 'tidb-lightning.test')) {
                        sh label: 'tidb-lightning.test', script: ' [ -f ./bin/tidb-lightning-ctl.test ] || make build_for_lightning_integration_test'
                    }
                    dir("bin") {
                        container("utils") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                            --pd=${OCI_TAG_PD} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --tiflash=${OCI_TAG_TIFLASH} \
                                            --fake-gcs-server=${OCI_TAG_FAKE_GCS_SERVER} \
                                            --kes=${OCI_TAG_KES} \
                                            --minio=${OCI_TAG_MINIO}
                                    """
                                }
                            }
                        }
                        sh """
                            mv tiflash tiflash_dir
                            ln -s tiflash_dir/tiflash tiflash

                            ls -alh .
                            ./pd-server -V
                            ./tikv-server -V
                            ./tiflash --version
                        """
                    }
                    // cache workspace for matrix pods
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch rev-${REFS.pulls[0].sha}"
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage("Test") {
                        environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls rev-${REFS.pulls[0].sha}"
                                }
                                sh label: "TEST_GROUP ${TEST_GROUP}", script: """#!/usr/bin/env bash
                                    chmod +x lightning/tests/*.sh
                                    if [ "${TEST_GROUP}" = "G00" ]; then
                                        ./lightning/tests/run_group_lightning_tests.sh others
                                    fi
                                    ./lightning/tests/run_group_lightning_tests.sh ${TEST_GROUP}
                                """
                            }
                        }
                        post{
                            unsuccessful {
                                sh label: "collect logs", script: """
                                    ls /tmp/lightning_test
                                    tar --warning=no-file-changed  -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/lightning_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                            success {
                                dir(REFS.repo) {
                                    sh label: "upload coverage", script: """
                                        ls -alh /tmp/group_cover
                                        gocovmerge /tmp/group_cover/cov.* > coverage.txt
                                        codecov --rootDir . --flags integration --file coverage.txt --branch origin/pr/${REFS.pulls[0].number} --sha ${REFS.pulls[0].sha} --pr ${REFS.pulls[0].number} || true
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
