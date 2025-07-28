// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_e2e_test.yaml'
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
        stage('Tests') {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('tidb/tests/graceshutdown') {
                    sh label: 'test graceshutdown', script: 'make && ./run-tests.sh'
                }
                dir('tidb/tests/globalkilltest') {
                    dir('bin') {
                        container('utils') {
                            retry(3) {
                                sh label: 'download binary', script: """
                                    script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                    chmod +x \$script
                                    \$script --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                                """
                            }
                        }
                    }
                    sh label: 'test globalkilltest', script: '''
                        make && ./run-tests.sh
                    '''
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
