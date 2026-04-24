// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
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
        timeout(time: 40, unit: 'MINUTES')
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
        stage("Prepare") {
            steps {
                dir(REFS.repo) {
                    cache(path: "./bin", includes: 'tidb-server', key: prow.getCacheKey('binary', REFS)) {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV}
                                    """
                                }
                            }
                        }
                    }
                    sh label: 'check version', script: '''
                        ls -alh bin/
                        ./bin/tidb-server -V
                        ./bin/tikv-server -V
                        ./bin/pd-server -V
                    '''
                }
            }
        }
        stage('Tests') {
            steps {
                dir(REFS.repo) {
                    dir('tests/graceshutdown') {
                        sh label: 'test graceshutdown', script: 'make && ./run-tests.sh'
                    }
                    dir('tests/globalkilltest') {
                        sh label: 'test globalkilltest', script: """
                            cp -r ${WORKSPACE}/tidb/bin bin
                            make && ./run-tests.sh
                        """
                    }
                }
            }
            post{
                failure {
                    script {
                        archiveArtifacts(artifacts: '/tmp/tidb_globalkilltest/*.log', fingerprint: false)
                        archiveArtifacts(artifacts: '/tmp/tidb_gracefulshutdown/*.log', fingerprint: false)
                    }
                }
            }
        }
    }
}
