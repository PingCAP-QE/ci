// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.5/pod-pull_integration_e2e_test_centos.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
                }
            }
        }
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
                    retry(3) {
                        sh label: 'download binary', script: """
                            # cd tests/integrationtest2 && ./download_integration_test_binaries.sh ${REFS.base_ref}
                            mkdir -p third_bin
                            wget -O tikv-server.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/devbuild/tikv/tikv/package?tag=v8.5.0-centos7_linux_amd64&file=tikv-v8.5.0-linux-amd64.tar.gz"
                            wget -O pd-server.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/devbuild/tikv/pd/package?tag=v8.5.0-centos7_linux_amd64&file=pd-v8.5.0-linux-amd64.tar.gz"
                            wget -O cdc.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/devbuild/pingcap/tiflow/package?tag=v8.5.0-centos7_linux_amd64&file=cdc-v8.5.0-linux-amd64.tar.gz"
                            wget -O tiflash.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/devbuild/pingcap/tiflash/package?tag=v8.5.0-centos7_linux_amd64&file=tiflash-v8.5.0-linux-amd64.tar.gz"
                            tar xzf tikv-server.tar.gz -C third_bin
                            tar xzf pd-server.tar.gz -C third_bin
                            tar xzf cdc.tar.gz -C third_bin
                            tar xzf tiflash.tar.gz && mv tiflash/* third_bin/ && rm -rf tiflash/
                            rm -rf tikv-server.tar.gz pd-server.tar.gz cdc.tar.gz tiflash.tar.gz
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
                        println "Test failed, archive the log"
                        archiveArtifacts artifacts: 'tidb/tests/integrationtest2/logs', fingerprint: true
                    }
                }
            }
        }
    }
}
