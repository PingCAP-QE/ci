// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_vector_search_test.yaml'
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
        timeout(time: 45, unit: 'MINUTES')
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
                    cache(path: "./bin", includes: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.base_sha}-${REFS.pulls[0].sha}") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                    script {
                         component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                         component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                         // Note tiflash need extract all files in tiflash dir (extract tar.gz to tiflash dir)
                         component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tiflash', REFS.base_ref, REFS.pulls[0].title, 'centos7/tiflash-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                    } 
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'test', script: """
                        cd tests/clusterintegrationtest/
                        ./run_mysql_tester.sh
                        ./run_python_tester.sh
                        ./run_upgra
                    """
                }
            }
            post{
                failure {
                    script {
                        println "Test failed, archive the log"
                        archiveArtifacts artifacts: 'tidb/tests/clusterintegrationtest/logs', fingerprint: true
                    }
                }
            }
        }
    }
}
