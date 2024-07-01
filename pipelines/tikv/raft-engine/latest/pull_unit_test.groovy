// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/raft-engine'
final POD_TEMPLATE_FILE = 'pipelines/tikv/raft-engine/latest/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    options {
        timeout(time: 50, unit: 'MINUTES')
        parallelsAlwaysFailFast()
        skipDefaultCheckout()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    env
                    hostname
                    df -h
                    free -hm
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("raft-engine") {
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
        stage('format') {
            steps {
                dir("raft-engine") {
                    sh label: 'format', script: """
                        make format
                        git diff --exit-code
                    """
                }
            }
        }
        stage('clippy') {
            steps {
                dir("raft-engine") {
                    sh label: 'clippy', script: """
                        export EXTRA_CARGO_ARGS="--fix"
                        make clippy
                    """
                }
            }
        }
        stage("Test") {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('raft-engine') {
                    sh label: 'test', script: """
                        export RUST_BACKTRACE=1
                        export EXTRA_CARGO_ARGS='--verbose'
                        make test
                    """
                    sh label: 'asan tests', script: """
                        export RUST_BACKTRACE=1
                        export RUSTFLAGS='-Zsanitizer=address'
                        export RUSTDOCFLAGS='-Zsanitizer=address'
                        export EXTRA_CARGO_ARGS='--verbose -Zbuild-std --target x86_64-unknown-linux-gnu
                        make test
                    """
                }
            }
        }
    }
}
