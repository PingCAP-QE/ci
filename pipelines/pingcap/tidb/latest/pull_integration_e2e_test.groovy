// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

final BASE_REF_SPECIAL = (REFS.base_ref ==~ /^release-fts-[0-9]+$/) ? 'master' : REFS.base_ref
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC = component.computeArtifactOciTagFromPR('ticdc', BASE_REF_SPECIAL, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final GIT_CREDENTIALS_ID = ''

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
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
            }
        }
        stage('Tests') {
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
                                            --ticdc-new=${OCI_TAG_TICDC}
                                    """
                                }
                            }
                        }
                        sh '''
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
