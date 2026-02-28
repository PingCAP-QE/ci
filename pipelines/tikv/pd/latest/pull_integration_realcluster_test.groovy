// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/latest/pod-pull_integration_realcluster_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')

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
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("pd") {
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
                dir('pd') {
                    container("golang") {
                        sh label: 'pd-server', script: '[ -f bin/pd-server ] || WITH_RACE=1 RUN_CI=1 make pd-server-basic'
                        sh 'mkdir -p third_bin'
                    }
                    container("utils") {
                        dir("third_bin") {
                            sh label: 'download peer component binaries', script: """
                                ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                    --tidb=${OCI_TAG_TIDB} \
                                    --tikv=${OCI_TAG_TIKV} \
                                    --tiflash=${OCI_TAG_TIFLASH}
                                chmod +x tidb-server tikv-server
                                mv tiflash/* ./ && rm -rf tiflash
                            """
                        }
                    }
                    container("golang") {
                        sh label: 'verify binaries', script: """
                            ls -alh third_bin/
                            bin/pd-server -V
                            third_bin/tikv-server -V
                            third_bin/tidb-server -V
                            third_bin/tiflash --version
                        """
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir('pd') {
                    sh label: "PD Real Cluster Check", script: """
                        make test-real-cluster
                    """
                }
            }
            post {
                failure {
                    sh label: "collect logs", script: """
                        ls /tmp/real_cluster/playground
                        tar -cvzf tiup-playground-output.tar.gz \$(find /tmp/real_cluster/playground -maxdepth 2 -type f -name "*.log")
                        ls -alh tiup-playground-output.tar.gz

                        tar -cvzf log-real-cluster-data.tar.gz /home/jenkins/.tiup/data
                        ls -alh log-real-cluster-data.tar.gz
                    """
                    archiveArtifacts artifacts: "tiup-playground-output.tar.gz, log-real-cluster-data.tar.gz", fingerprint: true
                }
            }
        }
    }
}
