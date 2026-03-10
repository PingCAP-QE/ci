// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/release-6.1/pod-pull_unit_test.yaml'
final LEGACY_CHUNK_COUNT = 20
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
        timeout(time: 150, unit: 'MINUTES')
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
                    git status
                    git log -1
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
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                            set -e
                            set -o pipefail
                            test="test"
                            if grep ci_test Makefile; then
                                test="ci_test"
                            fi
                            if EXTRA_CARGO_ARGS="--no-run --message-format=json" make ci_doc_test test | grep -E '^{.+}\$' > test.json ; then
                                set +o pipefail
                            else
                                if EXTRA_CARGO_ARGS="--no-run --message-format=json" make test | grep -E '^{.+}\$' > test.json ; then
                                    set +o pipefail
                                else
                                    EXTRA_CARGO_ARGS="--no-run" make test
                                    exit 1
                                fi
                            fi

                            wget https://raw.githubusercontent.com/PingCAP-QE/ci/main/scripts/tikv/tikv/release-6.1/gen_test_binary.py
                            python gen_test_binary.py

                            ls -alh archive-test-binaries
                            tar -cvf archive-test-binaries.tar archive-test-binaries
                            ls -alh archive-test-binaries.tar

                            chmod a+x test-chunk-*
                            tar czf test-artifacts.tar.gz test-chunk-* src tests components `ls target/*/deps/*plugin.so 2>/dev/null`
                            ls -alh test-artifacts.tar.gz
                            mkdir -p /home/jenkins/archives
                            mv archive-test-binaries.tar test-artifacts.tar.gz /home/jenkins/archives/
                        """
                    }
                }
            }
        }
        stage("Test") {
            options { timeout(time: 120, unit: 'MINUTES') }
            steps {
                dir('/home/jenkins/agent/tikv-presubmit/unit-test') {
                    sh label: "Setup test environment", script: """
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
                        tar xf test-artifacts.tar.gz && rm test-artifacts.tar.gz
                        tar xf archive-test-binaries.tar --strip-components=1 && rm archive-test-binaries.tar
                        ls -la
                        ls -alh target/debug/deps/
                        chown -R 1000:1000 target/
                    """
                    sh label: "run tests", script: """
                        ls -alh \$HOME/tikv-src
                        ls -alh /home/jenkins/tikv-src/
                        ls -alh /home/jenkins/tikv-src/target/debug/deps/
                        uname -a
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export RUST_BACKTRACE=1
                        export MALLOC_CONF=prof:true,prof_active:false
                        export CI=1
                        export LOG_FILE=target/my_test.log
                        export RUST_TEST_THREADS=1

                        for i in \$(seq 1 ${LEGACY_CHUNK_COUNT}); do
                            if [[ ! -f test-chunk-\${i} ]]; then
                                echo "test-chunk-\${i} not found, skipping remaining chunks"
                                break
                            fi
                            echo "Running test chunk \${i}"
                            ./test-chunk-\${i} 2>&1 | tee tests-\${i}.out
                            chunk_count=\$(grep nocapture test-chunk-\${i} | wc -l)
                            ok_count=\$(grep "test result: ok" tests-\${i}.out | wc -l)
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                                echo "test chunk \${i} pass"
                            else
                                grep "^    " tests-\${i}.out | tr -d '\\r' | grep :: | xargs -I@ awk 'BEGIN{print "---- log for @ ----\\n"}/start, name: @/{flag=1}{if (flag==1) print substr(\$0, length(\$1) + 2)}/end, name: @/{flag=0}END{print ""}' target/my_test.log
                                awk '/^failures/{flag=1}/^test result:/{flag=0}flag' tests-\${i}.out
                                gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                                exit 1
                            fi
                        done
                    """
                }
            }
            post {
                failure {
                    sh label: "collect logs", script: """
                        log_dir="/home/jenkins/tikv-src/target/"
                        output_archive="log-ut.tar.gz"

                        # Find all log files in the specified directory
                        log_files=\$(find "\$log_dir" -type f -name "*.log")

                        if [[ -n \$log_files ]]; then
                            tar -cvzf "\$output_archive" \$log_files
                            echo "Logs have been archived into \$output_archive"
                        else
                            echo "No log files found in \$log_dir"
                        fi
                        ls -alh "\$output_archive"
                    """
                    archiveArtifacts artifacts: "log-ut.tar.gz", allowEmptyArchive: true
                }
            }
        }
    }
}
