// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/release-6.1/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

final EXTRA_NEXTEST_ARGS = "-j 8"


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
        TIKV_TEST_MEMORY_DISK_MOUNT_POINT = "/home/jenkins/agent/memvolume"
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
                sh """
                    rm -rf /home/jenkins/tikv-src
                """
                dir("tikv") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                sh """
                    pwd & ls -alh 
                    mv ./tikv \$HOME/tikv-src
                    cd \$HOME/tikv-src
                    ln -s \$HOME/tikv-target \$HOME/tikv-src/target
                    pwd && ls -alh
                """
            }
        }
        stage('lint') {
            steps {
                dir("tikv") {
                    retry(2) {
                        sh label: 'Run lint: format', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            make format
                            git diff --quiet || (git diff; echo Please make format and run tests before creating a PR; exit 1)
                        """
                        sh label: 'Run lint: clippy', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export ROCKSDB_SYS_SSE=1
                            export RUST_BACKTRACE=1
                            export LOG_LEVEL=INFO
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            make clippy || (echo Please fix the clippy error; exit 1)
                        """
                    }
                }
            }
        }
        stage('build') {
            steps {
                dir("tikv") {
                    retry(2) {
                        sh label: 'Build test artifact', script: """
                            cd \$HOME/tikv-src
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export ROCKSDB_SYS_SSE=1
                            export RUST_BACKTRACE=1
                            export LOG_LEVEL=INFO
                            export CARGO_INCREMENTAL=0
                            export RUSTDOCFLAGS="-Z unstable-options --persist-doctests"
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            set -e
                            set -o pipefail

                        """
                    }
                }
            }
        }
        stage("Test") {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('/home/jenkins/agent/tikv-presubmit/unit-test') {
                    // TODO: add test command (not use nextest in current branch)
                }
            }
            post {
                failure {
                    sh label: "collect logs", script: """
                        ls /home/jenkins/tikv-src/target/
                        tar -cvzf log-ut.tar.gz \$(find /home/jenkins/tikv-src/target/ -type f -name "*.log")    
                        ls -alh  log-ut.tar.gz  
                    """
                    archiveArtifacts artifacts: "log-ut.tar.gz", fingerprint: true 
                }
            }
        }
    }
}
