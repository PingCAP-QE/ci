
// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIFLASH = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "cloud-engine-nextgen")
final OCI_TAG_TICDC = "master-nextgen"

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
        NEXT_GEN = '1' // enable build and test for Next Gen kernel type.
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID, true)
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir("${REFS.repo}/tests/integrationtest2/third_bin") {
                    container("utils") {
                        withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                            sh label: "prepare docker auth", script: '''
                                mkdir -p ~/.docker
                                cp ${DOCKER_CONFIG_JSON} ~/.docker/config.json
                            '''
                        }
                        sh """
                            script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                            chmod +x \$script
                            \${script} \
                                --pd=${OCI_TAG_PD} \
                                --tikv=${OCI_TAG_TIKV} \
                                --tikv-worker=${OCI_TAG_TIKV} \
                                --tiflash=${OCI_TAG_TIFLASH} \
                                --ticdc-new=${OCI_TAG_TICDC} \
                                --minio=RELEASE.2025-07-23T15-54-02Z
                        """
                    }
                    sh '''
                        ./tikv-server -V
                        ./tikv-worker -V
                        ./pd-server -V
                        ./tiflash --version
                        ./cdc version
                    '''
                }
            }
        }
        stage('Tests') {
            environment {
                MINIO_BIN_PATH = "third_bin/minio"
            }
            steps {
                dir("${REFS.repo}/tests/integrationtest2") {
                    sh label: 'test', script: './run-tests-next-gen.sh'
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
