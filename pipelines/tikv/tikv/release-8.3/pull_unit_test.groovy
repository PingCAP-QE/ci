// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/release-8.3/pod-pull_unit_test.yaml'
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
                        prow.setPRDescription(REFS)
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

                            # Build and generate a list of binaries
                            CUSTOM_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test_with_nextest | grep -E '^{.+}\$' > test.json
                            # Cargo metadata
                            cargo metadata --format-version 1 > test-metadata.json
                            # cp ${WORKSPACE}/scripts/tikv/tikv/gen_test_binary_json.py ./gen_test_binary_json.py
                            wget https://raw.githubusercontent.com/PingCAP-QE/ci/main/scripts/tikv/tikv/gen_test_binary_json.py
                            python gen_test_binary_json.py
                            cat test-binaries.json

                            # archive test artifacts
                            ls -alh archive-test-binaries
                            tar -cvf archive-test-binaries.tar archive-test-binaries
                            ls -alh archive-test-binaries.tar
                            tar czf test-artifacts.tar.gz test-binaries test-binaries.json test-metadata.json Cargo.toml cmd src tests components .config `ls target/*/deps/*plugin.so 2>/dev/null`
                            ls -alh test-artifacts.tar.gz
                            mkdir -p /home/jenkins/archives
                            mv test-artifacts.tar.gz archive-test-binaries.tar /home/jenkins/archives/
                        """
                    }
                }
            }
        }
        stage("Test") {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir('/home/jenkins/agent/tikv-presubmit/unit-test') {
                    sh label: "clean up", script: """
                        rm -rf /home/jenkins/tikv-*
                        ls -alh /home/jenkins/
                        ln -s `pwd` \$HOME/tikv-src
                        mkdir -p target/debug
                        uname -a
                        df -h
                        free -hm

                        # prepare test artifacts
                        cp /home/jenkins/archives/test-artifacts.tar.gz .
                        cp /home/jenkins/archives/archive-test-binaries.tar .
                        tar -xf test-artifacts.tar.gz
                        tar xf archive-test-binaries.tar --strip-components=1
                        rm -f test-artifacts.tar.gz archive-test-binaries.tar
                        ls -la
                        ls -alh target/debug/deps/
                    """
                    sh """
                    ls -alh \$HOME/tikv-src
                    ls -alh /home/jenkins/tikv-src/
                    ls -alh /home/jenkins/tikv-src/target/debug/deps/
                    export RUSTFLAGS=-Dwarnings
                    export FAIL_POINT=1
                    export RUST_BACKTRACE=1
                    export MALLOC_CONF=prof:true,prof_active:false
                    # export CI=1  # TODO: remove this
                    export LOG_FILE=/home/jenkins/tikv-src/target/my_test.log

                    if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json --partition count:1/2 ${EXTRA_NEXTEST_ARGS}; then
                        echo "test pass"
                    else
                        # test failed
                        gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                        exit 1
                    fi
                    if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json --partition count:2/2 ${EXTRA_NEXTEST_ARGS}; then
                        echo "test pass"
                    else
                        # test failed
                        gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                        exit 1
                    fi
                    """
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
