// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_br_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')

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
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'br-integration-test')) {
                        // build br.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            [ -f ./bin/tidb-server ] || (make failpoint-enable && make && make failpoint-disable)
                            [ -f ./bin/br.test ] || make build_for_br_integration_test
                            ls -alh ./bin
                            ./bin/tidb-server -V
                        """
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
                    stage('Prepare') {
                        steps {
                            dir("tidb") {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    sh 'ls -lh'
                                }
                            }
                            dir("third_party_download") {
                                script {
                                    retry(2) {
                                        container("utils") {
                                            sh label: "download third_party", script: """
                                                ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --tiflash=${OCI_TAG_TIFLASH}
                                                rm -rf bin/ && mkdir -p bin
                                                mv pd-server tikv-server bin/ 2>/dev/null || true
                                                if [ -d tiflash ]; then
                                                    mv tiflash/* bin/ 2>/dev/null || true
                                                    rmdir tiflash 2>/dev/null || true
                                                fi
                                                ls -alh bin/
                                                ./bin/pd-server -V
                                                ./bin/tikv-server -V
                                                ./bin/tiflash --version
                                            """
                                        }
                                    }
                                }
                            }
                            dir('tidb') {
                                sh label: "check all tests added to group", script: """#!/usr/bin/env bash
                                    chmod +x br/tests/*.sh
                                    ./br/tests/run_group_br_tests.sh others
                                """
                                // build br.test for integration test
                                // only build binarys if not exist, use the cached binarys if exist
                                cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'br-integration-test')) {
                                    sh label: "prepare", script: """
                                        [ -f ./bin/tidb-server ] || (make failpoint-enable && make && make failpoint-disable)
                                        [ -f ./bin/br.test ] || make build_for_br_integration_test
                                        ls -alh ./bin
                                        ./bin/tidb-server -V
                                    """
                                }
                                sh label: "prepare", script: """
                                    cp -r ../third_party_download/bin/* ./bin/
                                    ls -alh ./bin
                                """
                            }
                        }
                    }
                    stage("Test") {
                        environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
                        options { timeout(time: 60, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                sh label: "TEST_GROUP ${TEST_GROUP}", script: """#!/usr/bin/env bash
                                    chmod +x br/tests/*.sh
                                    ./br/tests/run_group_br_tests.sh ${TEST_GROUP}
                                """
                            }
                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/backup_restore_test
                                    tar --warning=no-file-changed -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/backup_restore_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                            success {
                                dir('tidb'){
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
