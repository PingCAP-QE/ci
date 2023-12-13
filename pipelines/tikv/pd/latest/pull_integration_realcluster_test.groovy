// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/latest/pod-pull_integration_realcluster_test.yaml'
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
                        sh label: 'pd-server', script: '[ -f bin/pd-server ] || make'
                        sh label: 'other-server', script: """
                        chmod +x ${WORKSPACE}/scripts/artifacts/*.sh
                        ${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --tidb=${REFS.base_ref} --tikv=${REFS.base_ref} --tiflash=${REFS.base_ref}
                        rm -rf third_bin/bin && mv third_bin/* bin/ && ls -alh bin/
                        bin/pd-server -V
                        bin/tikv-server -V
                        bin/tiflash --version
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
                        cd tests/integrations/realtiup
                        make check
                    """
                }
            }
        }
    }
}
