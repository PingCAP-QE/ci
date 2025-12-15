// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-7.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-7.5/pod-pull_integration_binlog_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
prow.setPRDescription(REFS)

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
        GITHUB_TOKEN = credentials('github-bot-token')
    }
    options {
        timeout(time: 65, unit: 'MINUTES')
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
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-binlog") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb-binlog/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-binlog/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb-binlog.git', 'tidb-binlog', REFS.base_ref, REFS.pulls[0].title, "")
                                sh """
                                    git status
                                    git log -1
                                """
                            }
                        }
                    }
                }
                dir("tidb-tools") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb-tools/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-tools/rev-']) {
                        retry(2) {
                            script {
                                // from v6.0.0, tidb-tools only maintain master branch
                                component.checkout('https://github.com/pingcap/tidb-tools.git', 'tidb-tools', "master", REFS.pulls[0].title, "")
                                sh """
                                    git status
                                    git log -1
                                """
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: 'tidb-server', script: 'make server'
                }
                dir('tidb-binlog') {
                    sh label: 'prepare', script: """
                        make build
                        ls -alh bin/
                    """
                    script {
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master")
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master")
                    }
                    sh label: 'list bin', script: """
                        ls -alh bin/
                    """
                }
                dir('tidb-tools') {
                    sh label: 'prepare', script: """
                        make build
                        ls -alh bin/
                        rm -f bin/{ddl_checker,importer}
                        ls -alh bin/
                    """
                }
            }
        }
        stage('Binlog Integration Test') {
            steps {
                dir('tidb-binlog') {
                    sh label: 'run test', script: """
                        cp ../tidb-tools/bin/* bin/
                        cp ../tidb/bin/tidb-server bin/
                        ls -alh bin/
                        KAFKA_ADDRS=127.0.0.1:9092  make integration_test
                    """
                }
            }
        }
    }
}
