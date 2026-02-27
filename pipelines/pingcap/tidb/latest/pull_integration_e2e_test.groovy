// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC_NEW = component.computeArtifactOciTagFromPR('ticdc', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
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
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'  // cache mirror for us-docker.pkg.dev/pingcap-testing-account/hub
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
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
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir("${REFS.repo}/tests/integrationtest2") {
                    dir("third_bin") {
                        container("utils") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                            --pd=${OCI_TAG_PD} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --tiflash=${OCI_TAG_TIFLASH} \
                                            --ticdc-new=${OCI_TAG_TICDC_NEW}
                                    """
                                }
                            }
                        }
                        sh '''
                            mv tiflash tiflash_dir
                            ln -s `pwd`/tiflash_dir/tiflash tiflash

                            ./tikv-server -V
                            ./pd-server -V
                            ./tiflash --version
                        '''
                    }
                    sh './run-tests.sh'
                }
            }
            post{
                failure {
                    archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                }
            }
        }
    }
}
