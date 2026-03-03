// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/release-8.1/pod-pull_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
prow.setPRDescription(REFS)
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 50, unit: 'MINUTES')
        parallelsAlwaysFailFast()
        skipDefaultCheckout()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
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
                dir(REFS.repo) {
                    sh label: 'Prepare tikv-server', script: 'make release'
                }
                dir('bin') {
                    container('utils') {
                        retry(2) {
                            sh label: 'download components', script: """
                                ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --tidb=${OCI_TAG_TIDB}
                            """
                        }
                    }
                }
            }
        }
        stage('Tests') {
            stages{
                stage('copr test') {
                    options { timeout(time: 30, unit: 'MINUTES') }
                    steps {
                        dir('tidb') {
                            cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkoutSupportBatch('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, REFS, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                        dir('copr-test') {
                            cache(path: "./", includes: '**/*', key: "git/tikv/copr-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/tikv/copr-test/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkoutSupportBatch('https://github.com/tikv/copr-test.git', 'copr-test', REFS.base_ref, REFS.pulls[0].title, REFS, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                            container('golang') {
                                sh label: 'Push Down Test', script: '''
                                    pd_bin=${WORKSPACE}/bin/pd-server \
                                    tikv_bin=${WORKSPACE}/tikv/bin/tikv-server \
                                    tidb_src_dir=${WORKSPACE}/tidb \
                                    make push-down-test
                                '''
                            }
                        }
                    }
                    post {
                        failure {
                            echo "TODO: archive logs"
                        }
                    }
                }
                stage('compatible test') {
                    steps {
                        dir("tidb-test") {
                            cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkoutSupportBatch('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, REFS, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                        container('golang') {
                            dir('tidb-test/compatible_test') {
                                sh label: 'compile test binary', script: './build.sh'
                                sh label: 'run test', script: '''
                                    chmod +x ${WORKSPACE}/scripts/tikv/tikv/run_compatible_tests.sh

                                    TIKV_PATH="${WORKSPACE}/tikv/bin/tikv-server" \
                                    TIDB_PATH="${WORKSPACE}/bin/tidb-server" \
                                    PD_PATH="${WORKSPACE}/bin/pd-server" \
                                    OLD_BINARY="${WORKSPACE}/bin/tikv-server" \
                                    ${WORKSPACE}/scripts/tikv/tikv/run_compatible_tests.sh
                                '''
                            }
                        }
                    }
                    post {
                        failure {
                            echo "TODO: archive logs"
                        }
                    }
                }
            }
        }
    }
}
