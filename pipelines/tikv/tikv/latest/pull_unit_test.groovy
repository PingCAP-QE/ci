// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/latest/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final CHUNK_COUNT = 2
final EXTRA_NEXTEST_ARGS = "-j 8"


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
            workspaceVolume emptyDirWorkspaceVolume(memory: true)
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tikv") {
                    // cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                    //     retry(2) {
                    //         script {
                    //             prow.checkoutRefs(REFS)
                    //         }
                    //     }
                    // }
                    sh """
                    git clone --depth 1  --branch master https://github.com/tikv/tikv.git .
                    git status
                    git show --oneline -s
                    """
                }
            }
        }
        stage('Prepare') {
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
                                set -o pipefail

                                # Build and generate a list of binaries
                                CUSTOM_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test_with_nextest | grep -E '^{.+}\$' > test.json
                                # Cargo metadata
                                cargo metadata --format-version 1 > test-metadata.json
                                cp ${WORKSPACE}/scripts/tikv/tikv/gen_test_binary_json.py ./gen_test_binary_json.py
                                python3 gen_test_binary_json.py
                                cat test-binaries.json
                            """
                            // sh label: 'Post build artifact', script: """
                            //     cd \$HOME/tikv-src
                            //     tar czf test-artifacts.tar.gz test-binaries test-binaries.json test-metadata.json Cargo.toml cmd src tests components .config `ls target/*/deps/*plugin.so 2>/dev/null`
                            //     ls -alh test-artifacts.tar.gz
                            // """
                            // stash name: 'test-artifacts', includes: 'test-artifacts.tar.gz'
                        }
                    }
                }
            }
        }
        stages {
            stage("Test") {
                options { timeout(time: 30, unit: 'MINUTES') }
                steps {
                    dir('tikv') {
                        // unstash 'test-artifacts'
                        sh """
                        cd \$HOME/tikv-src
                        ls -alh
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export RUST_BACKTRACE=1
                        export MALLOC_CONF=prof:true,prof_active:false
                        export CI=1  # TODO: remove this
                        export LOG_FILE=$WORKSPACE/tikv/target/my_test.log

                        if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json ${EXTRA_NEXTEST_ARGS}; then
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
                            ls \$WORKSPACE/tikv/target/
                            tar -cvzf log-${CHUNK_SUFFIC}.tar.gz \$(find $WORKSPACE/tikv/target/ -type f -name "*.log")    
                            ls -alh  log-${CHUNK_SUFFIC}.tar.gz  
                        """
                        archiveArtifacts artifacts: "log-ut.tar.gz", fingerprint: true 
                    }
                }
            }
        }
    }
}
