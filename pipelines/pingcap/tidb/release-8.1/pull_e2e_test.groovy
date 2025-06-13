// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.1/pod-pull_e2e_test.yaml'
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
                    sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                    retry(3) {
                        sh label: 'download binary', script: """
                            chmod +x \${WORKSPACE}/scripts/artifacts/*.sh
                            \${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/tikv-server bin/
                            mv third_bin/pd-server bin/
                            ls -alh bin/
                            chmod +x bin/*
                            ./bin/tikv-server -V
                            ./bin/pd-server -V
                        """
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'check version', script: """
                    ls -alh bin/
                    ./bin/tidb-server -V
                    ./bin/tikv-server -V
                    ./bin/pd-server -V
                    """
                    sh label: 'test graceshutdown', script: """
                    cd tests/graceshutdown && make
                    ./run-tests.sh
                    """
                    sh label: 'test globalkilltest', script: """
                    cd tests/globalkilltest && make
                    cp ${WORKSPACE}/tidb/bin/tikv-server ${WORKSPACE}/tidb/bin/pd-server ./bin/
                    PD=./bin/pd-server  TIKV=./bin/tikv-server ./run-tests.sh
                    """
                }
            }
            post{
                failure {
                    script {
                        println "Test failed, archive the log"
                        archiveArtifacts artifacts: '/tmp/tidb_globalkilltest/*.log', fingerprint: true
                        archiveArtifacts artifacts: '/tmp/tidb_gracefulshutdown/*.log', fingerprint: true
                    }
                }
            }
        }
    }
}
