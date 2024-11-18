// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.1/pod-pull_integration_binlog_test.yaml'
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
        GITHUB_TOKEN = credentials('github-bot-token')
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
                                component.checkout('git@github.com:pingcap/tidb-binlog.git', 'tidb-binlog', REFS.base_ref, REFS.pulls[0].title, "")
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
                                component.checkout('git@github.com:pingcap/tidb-tools.git', 'tidb-tools', master, REFS.pulls[0].title, "")
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
                    sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                }
                dir('tidb-binlog') {
                    sh label: 'prepare', script: """
                        make build
                        ls -alh bin/
                        chmod +x ${WORKSPACE}/scripts/pingcap/tidb-binlog/*.sh
                        ${WORKSPACE}/scripts/pingcap/tidb-binlog/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                        mv third_bin/* bin/
                        ls -alh bin/
                    """
                }
                dir('tidb-tools') {
                    sh label: 'prepare', script: """
                        make build
                        ls -alh bin/
                        rm -f bin/{ddl_checker,importer}
                        ls -alh bin/
                        cp -r bin/* ../tidb-binlog/bin/
                    """
                }
            }
        }
        stage('Binlog Integration Test') {
            steps {
                dir('tidb-binlog') {
                    sh label: 'run test', script: """
                        ls -alh bin/
                        KAFKA_ADDRS=127.0.0.1:9092  make integration_test
                    """
                }
            }
        }
    }
}
