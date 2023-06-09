// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb-mergeci"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_copr_test.yaml'
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tikv-copr-test") {
                    cache(path: "./", filter: '**/*', key: "git/tikv/copr-test/rev-${REFS.base_sha}", restoreKeys: ['git/tikv/copr-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/tikv/copr-test.git', 'copr-test', REFS.base_ref, REFS.pulls[0].title, "")
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/pull_mysql_test/rev-${BUILD_TAG}") {
                        container("golang") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                            sh label: 'download binary', script: """
                            chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                            ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            mv third_bin/* bin/
                            ls -alh bin/
                            """
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'  
                    sh label: 'tikv-server', script: 'ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V'
                    sh label: 'pd-server', script: 'ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V'  
                }
                dir('tikv-copr-test') {
                    sh label: "Push Down Test", script: """
                        #!/usr/bin/env bash
                        pd_bin=${WORKSPACE}/tidb/bin/pd-server \
                        tikv_bin=${WORKSPACE}/tidb/bin/tikv-server \
                        tidb_src_dir=${WORKSPACE}/tidb \
                        make push-down-test
                    """
                }
            }               
        }
    }
}
