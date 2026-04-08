// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final SELF_DIR = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}"
final POD_TEMPLATE_FILE = "${SELF_DIR}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final BASE_REF_SPECIAL = (REFS.base_ref ==~ /^release-fts-[0-9]+$/) ? 'master' : REFS.base_ref
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', BASE_REF_SPECIAL, REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC = component.computeArtifactOciTagFromPR('ticdc', BASE_REF_SPECIAL, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TICI = component.computeArtifactOciTagFromPR('tici', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_MINIO = 'RELEASE.2025-07-23T15-54-02Z'
final GIT_CREDENTIALS_ID = ''

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    stages {
        stage('Checkout') {
            steps {
                dir('tidb') {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb/tests/integrationtest2/third_bin') {
                    script {
                        retry(2) {
                            container("utils") {
                                sh label: 'download binary', script: """
                                    script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                    chmod +x \$script
                                    \$script \
                                        --pd=${OCI_TAG_PD} \
                                        --tikv=${OCI_TAG_TIKV} \
                                        --tiflash=${OCI_TAG_TIFLASH} \
                                        --ticdc-new=${OCI_TAG_TICDC} \
                                        --tici=${OCI_TAG_TICI} \
                                        --minio=${OCI_TAG_MINIO}
                                """
                            }
                            sh '''
                                ls -alh .
                                ./tikv-server -V
                                ./pd-server -V
                                ./tiflash --version
                                ./tici-server -V
                                ./cdc version
                            '''
                        }
                    }
                }
            }
        }
        stage('Tests') {
            environment {
                TICI_BIN = "third_bin/tici-server"
                MINIO_BIN = "third_bin/minio"
                MINIO_MC_BIN = "third_bin/mc"
            }
            steps {
                dir('tidb/tests/integrationtest2') {
                    sh label: 'test', script: './run-tests.sh -t tici'
                }
            }
            post{
                always {
                    dir('tidb/tests/integrationtest2') {
                        junit(testResults: 'report/tici_*.xml', allowEmptyResults: true)
                    }
                }
                failure {
                    script {
                        archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
