// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_integration_lightning_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final PR_TITLE = (REFS.pulls?.size() ?: 0) > 0 ? REFS.pulls[0].title : ''
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, PR_TITLE, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, PR_TITLE, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, PR_TITLE, 'master')
final OCI_TAG_FAKE_GCS_SERVER = 'v1.54.0'
final OCI_TAG_KES = 'v0.14.0'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'

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
        OCI_ARTIFACT_HOST_COMMUNITY = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir("tidb") {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir("third_party_download") {
                    dir("bin") {
                        container("utils") {
                            retry(2) {
                                sh label: "download third_party", script: """
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
                        sh label: "verify third_party", script: '''
                            if [[ -d tiflash && ! -L tiflash ]]; then
                                rm -rf tiflash_dir
                                mv tiflash tiflash_dir
                            fi
                            if [[ -f tiflash_dir/tiflash ]]; then
                                # Use a relative link so cache restore in matrix pods keeps tiflash executable resolvable.
                                ln -sfn "tiflash_dir/tiflash" tiflash
                            fi

                            ls -alh .
                            ./pd-server -V
                            ./tikv-server -V
                            ./tiflash --version
                        '''
                    }
                }
                dir('tidb') {
                    sh label: "check all tests added to group", script: """#!/usr/bin/env bash
                        chmod +x lightning/tests/*.sh
                        ./lightning/tests/run_group_lightning_tests.sh others
                    """
                    sh label: "prepare build", script: """#!/usr/bin/env bash
                        [ -f ./bin/tidb-server ] || make
                        [ -f ./bin/tidb-lightning.test ] || make build_for_lightning_integration_test
                        ls -alh ./bin
                        ./bin/tidb-server -V
                    """
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/lightning-tests") {
                        sh label: "prepare cache binary", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
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
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/lightning-tests") {
                                    sh label: "TEST_GROUP ${TEST_GROUP}", script: """#!/usr/bin/env bash
                                        chmod +x lightning/tests/*.sh
                                        ./lightning/tests/run_group_lightning_tests.sh ${TEST_GROUP}
                                    """
                                }
                            }

                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """#!/usr/bin/env bash
                                    ls /tmp/lightning_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/lightning_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }
}
