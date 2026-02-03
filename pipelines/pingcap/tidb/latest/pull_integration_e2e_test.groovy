// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC = component.computeArtifactOciTagFromPR('ticdc', REFS.base_ref, REFS.pulls[0].title, 'master')

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
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir('tidb') {
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
                    script {
                        retry(3) {
                            container("utils") {
                                dir("tests/integrationtest2") {
                                    sh label: 'download binary', script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --tiflash=${OCI_TAG_TIFLASH} --ticdc=${OCI_TAG_TICDC}
                                        mkdir -p third_bin
                                        mv pd-server tikv-server cdc third_bin/ 2>/dev/null || true
                                        if [ -d tiflash ]; then
                                            mv tiflash/* third_bin/ 2>/dev/null || true
                                            rmdir tiflash 2>/dev/null || true
                                        fi
                                        ls -alh third_bin/
                                        ./third_bin/tikv-server -V
                                        ./third_bin/pd-server -V
                                        ./third_bin/tiflash --version
                                        ./third_bin/cdc version
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'test', script: """
                        cd tests/integrationtest2 && ./run-tests.sh
                    """
                }
            }
            post{
                failure {
                    script {
                        archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
