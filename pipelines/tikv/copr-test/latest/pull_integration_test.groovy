// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/tikv/copr-test/latest/pod-pull_integration_test.yaml'
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
                dir("tikv-copr-test") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(3) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, "tidb")
                            }
                            sh """
                            git status
                            git log -1
                            """
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tikv-copr-test') {
                    script {
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin')
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin')
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir('tikv-copr-test') {
                    sh label: 'check version', script: """
                    ls -alh bin/
                    ./bin/tikv-server -V
                    ./bin/pd-server -V
                    """
                }
                dir('tikv-copr-test') {
                    sh label: "Push Down Test", script: """#!/usr/bin/env bash
                        export pd_bin=${WORKSPACE}/tikv-copr-test/bin/pd-server
                        export tikv_bin=${WORKSPACE}/tikv-copr-test/bin/tikv-server
                        export tidb_src_dir=${WORKSPACE}/tidb
                        make push-down-test
                    """
                }
            }
        }
    }
}
