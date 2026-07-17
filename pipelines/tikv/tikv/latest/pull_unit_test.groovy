// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tikv"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/latest/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

final SRC_DIR = 'tikv-src'
final TARGET_DIR = 'tikv-target'
final ARCHIVE_DIR = 'archives'
final UNIT_TEST_DIR = 'unit-test'
final TEST_ARTIFACTS = 'test-artifacts.tar.gz'
final TEST_BINARIES_ARCHIVE = 'archive-test-binaries.tar'
final EXTRA_NEXTEST_ARGS = "-j 8"

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
            retries 2
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
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                sh label: 'Clean workspace', script: """
                    rm -rf \
                        ${WORKSPACE}/${SRC_DIR} \
                        ${WORKSPACE}/${TARGET_DIR} \
                        ${WORKSPACE}/${ARCHIVE_DIR} \
                        ${WORKSPACE}/${UNIT_TEST_DIR}
                """
                dir("tikv") {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID)
                    }
                }
                sh label: 'Prepare source tree', script: """
                    pwd && ls -alh
                    mv ./tikv ${WORKSPACE}/${SRC_DIR}
                    cd ${WORKSPACE}/${SRC_DIR}
                    # Hotfix: some CI images may leave a non-directory target path.
                    rm -rf ${WORKSPACE}/${SRC_DIR}/target
                    mkdir -p ${WORKSPACE}/${TARGET_DIR}
                    ln -sfn ${WORKSPACE}/${TARGET_DIR} ${WORKSPACE}/${SRC_DIR}/target
                    pwd && ls -alh
                """
            }
        }
        stage('lint') {
            steps {
                retry(2) {
                    sh label: 'Run lint: format', script: """
                        cd ${WORKSPACE}/${SRC_DIR}
                        export RUSTFLAGS=-Dwarnings
                        make format
                        git diff --quiet || (git diff; echo Please make format and run tests before creating a PR; exit 1)
                    """
                    sh label: 'Run lint: clippy', script: """
                        cd ${WORKSPACE}/${SRC_DIR}
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export ROCKSDB_SYS_SSE=1
                        export RUST_BACKTRACE=1
                        export LOG_LEVEL=INFO

                        make clippy || (echo Please fix the clippy error; exit 1)
                    """
                }
            }
        }
        stage('build') {
            steps {
                retry(2) {
                    sh label: 'Build test artifact', script: """
                        cd ${WORKSPACE}/${SRC_DIR}
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export ROCKSDB_SYS_SSE=1
                        export RUST_BACKTRACE=1
                        export LOG_LEVEL=INFO
                        export CARGO_INCREMENTAL=0
                        export RUSTDOCFLAGS="-Z unstable-options --persist-doctests"

                        set -e
                        set -o pipefail

                        # Build and generate a list of binaries
                        CUSTOM_TEST_COMMAND="nextest list" EXTRA_CARGO_ARGS="--message-format json --list-type binaries-only" make test_with_nextest | grep -E '^{.+}\$' > test.json
                        # Cargo metadata
                        cargo metadata --format-version 1 > test-metadata.json
                        wget https://cdn.jsdelivr.net/gh/PingCAP-QE/ci@main/scripts/tikv/tikv/gen_test_binary_json.py
                        export TIKV_SRC_DIR=${WORKSPACE}/${SRC_DIR}
                        python gen_test_binary_json.py
                        cat test-binaries.json

                        # archive test artifacts
                        ls -alh archive-test-binaries
                        tar -cvf ${TEST_BINARIES_ARCHIVE} archive-test-binaries
                        ls -alh ${TEST_BINARIES_ARCHIVE}
                        tar czf ${TEST_ARTIFACTS} test-binaries test-binaries.json test-metadata.json Cargo.toml cmd src tests components .config `ls target/*/deps/*plugin.so 2>/dev/null`
                        ls -alh ${TEST_ARTIFACTS}
                        mkdir -p ${WORKSPACE}/${ARCHIVE_DIR}
                        mv ${TEST_ARTIFACTS} ${TEST_BINARIES_ARCHIVE} ${WORKSPACE}/${ARCHIVE_DIR}/
                    """
                }
            }
        }
        stage("Test") {
            options { timeout(time: 30, unit: 'MINUTES') }
            steps {
                dir("${WORKSPACE}/${UNIT_TEST_DIR}") {
                    sh label: "Prepare unit test workspace", script: """
                        rm -rf ${WORKSPACE}/${SRC_DIR} ${WORKSPACE}/${TARGET_DIR}
                        ls -alh ${WORKSPACE}/
                        ln -s `pwd` ${WORKSPACE}/${SRC_DIR}
                        mkdir -p target/debug
                        uname -a
                        df -h
                        free -hm

                        # prepare test artifacts
                        cp ${WORKSPACE}/${ARCHIVE_DIR}/${TEST_ARTIFACTS} .
                        cp ${WORKSPACE}/${ARCHIVE_DIR}/${TEST_BINARIES_ARCHIVE} .
                        tar -xf ${TEST_ARTIFACTS}
                        tar xf ${TEST_BINARIES_ARCHIVE} --strip-components=1
                        rm -f ${TEST_ARTIFACTS} ${TEST_BINARIES_ARCHIVE}
                        ls -la
                        ls -alh target/debug/deps/
                    """
                    sh label: "Run nextest", script: """
                    ls -alh ${WORKSPACE}/${SRC_DIR}/
                    ls -alh ${WORKSPACE}/${SRC_DIR}/target/debug/deps/
                    export RUSTFLAGS=-Dwarnings
                    export FAIL_POINT=1
                    export RUST_BACKTRACE=1
                    export MALLOC_CONF=prof:true,prof_active:false
                    # export CI=1  # TODO: remove this
                    export LOG_FILE=${WORKSPACE}/${SRC_DIR}/target/my_test.log

                    for partition in 1/2 2/2; do
                        if cargo nextest run -P ci --binaries-metadata test-binaries.json --cargo-metadata test-metadata.json --partition count:\${partition} ${EXTRA_NEXTEST_ARGS}; then
                            echo "partition \${partition} pass"
                        else
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
                        log_dir=${WORKSPACE}/${SRC_DIR}/target
                        tmp_file=\$(mktemp)
                        if [ -d "\${log_dir}" ]; then
                            find "\${log_dir}" -type f -name "*.log" > "\${tmp_file}"
                        fi

                        if [ -s "\${tmp_file}" ]; then
                            tar --warning=no-file-changed -czf log-ut.tar.gz -T "\${tmp_file}" || true
                        else
                            tar -czf log-ut.tar.gz --files-from /dev/null
                        fi
                        rm -f "\${tmp_file}"
                        ls -alh log-ut.tar.gz
                    """
                    archiveArtifacts artifacts: "log-ut.tar.gz", fingerprint: true
                }
            }
        }
    }
}
