// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.5-20241101-v6.5.7/pod-pull_integration_copr_test.yaml'
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
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tikv-copr-test") {
                    cache(path: "./", includes: '**/*', key: "git/tikv/copr-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/tikv/copr-test/rev-']) {
                        retry(3) {
                            script {
                                component.checkout('https://github.com/tikv/copr-test.git', 'copr-test', REFS.base_ref, REFS.pulls[0].title, "")
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
                dir('tidb') {
                    container("golang") {
                        retry(2) {
                            sh label: 'download binary', script: """
                            chmod +x ${WORKSPACE}/scripts/artifacts/*.sh
                            ${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --pd=${REFS.base_ref} --tikv=${REFS.base_ref}
                            rm -rf bin/ && mkdir -p bin/
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
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir('tidb') {
                    sh label: 'check version', script: """
                    ls -alh bin/
                    ./bin/tikv-server -V
                    ./bin/pd-server -V
                    """
                }
                dir('tikv-copr-test') {
                    sh label: "Push Down Test", script: """#!/usr/bin/env bash
                        export pd_bin=${WORKSPACE}/tidb/bin/pd-server
                        export tikv_bin=${WORKSPACE}/tidb/bin/tikv-server
                        export tidb_src_dir=${WORKSPACE}/tidb
                        make push-down-test
                    """
                }
            }
        }
    }
}
