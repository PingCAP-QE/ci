def chunk_count = 20

stage("Prepare") {
    def clippy = {
        node("build_tikv_cache") {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            def is_lint_passed = false
            container("rust") {
                is_lint_passed = (sh(label: 'Try to skip linting', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/lint_passed') == 0)
                println "Skip linting: ${is_lint_passed}"
            }

            if (!is_lint_passed) {
                container("rust") {
                    sh label: 'Prepare workspace', script: """
                        cd \$HOME/tikv-src
                        ln -s \$HOME/tikv-target \$HOME/tikv-src/target
                        ln -s \$HOME/tikv-git \$HOME/tikv-src/.git
                        if [[ "${ghprbPullId}" == 0 ]]; then
                            git fetch origin
                        else
                            git fetch origin refs/pull/${ghprbPullId}/head
                        fi
                        git checkout -f ${ghprbActualCommit}
                    """

                    sh label: 'Run lint: format', script: """
                        export RUSTFLAGS=-Dwarnings
                        make format && git diff --quiet || (git diff; echo Please make format and run tests before creating a PR; exit 1)
                    """

                    sh label: 'Run lint: clippy', script: """
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export ROCKSDB_SYS_SSE=1
                        export RUST_BACKTRACE=1
                        export LOG_LEVEL=INFO
                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                        fi
                        make clippy || (echo Please fix the clippy error; exit 1)
                    """

                    sh label: 'Post-lint: Save lint status', script: """
                    echo 1 > lint_passed
                    curl -F tikv_test/${ghprbActualCommit}/lint_passed=@lint_passed ${FILE_SERVER2_URL}/upload
                    """
                }
            }
        }
    }

    def build = {
        node("build_tikv_cache") {
            println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

            def is_artifact_existed = false
            container("rust") {
                is_artifact_existed = (sh(label: 'Try to skip building test artifact', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/build_passed') == 0)
                println "Skip building test artifact: ${is_artifact_existed}"
            }

            if (!is_artifact_existed) {
                container("rust") {
                    sh label: 'Prepare workspace', script: """
                        cd \$HOME/tikv-src
                        ln -s \$HOME/tikv-target \$HOME/tikv-src/target
                        ln -s \$HOME/tikv-git \$HOME/tikv-src/.git
                        if [[ "${ghprbPullId}" == 0 ]]; then
                            git fetch origin
                        else
                            git fetch origin refs/pull/${ghprbPullId}/head
                        fi
                        git checkout -f ${ghprbActualCommit}
                    """

                    sh label: 'Build test artifact', script: """
                    export RUSTFLAGS=-Dwarnings
                    export FAIL_POINT=1
                    export ROCKSDB_SYS_SSE=1
                    export RUST_BACKTRACE=1
                    export LOG_LEVEL=INFO
                    export CARGO_INCREMENTAL=0

                    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                    if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                    fi

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
                    """

                    sh label: 'Post-build: Check SSE instructions', script: """
                    if [ -f scripts/check-sse4_2.sh ]; then
                        sh scripts/check-sse4_2.sh
                    fi
                    """

                    sh label: 'Post-build: Check Jemalloc linking', script: """
                    if [ -f scripts/check-bins-for-jemalloc.sh ]; then
                        sh scripts/check-bins-for-jemalloc.sh
                    fi
                    """

                    sh label: 'Post-build: Upload test artifacts', script: """
                    cat test.json| grep -v proc-macro | jq -r "select(.profile.test == true) | .filenames[]" | sort -u > test.list
                    total=0
                    for bin in `cat test.list`; do
                        count=`\$bin --list | wc -l`
                        if [[ \$count -gt 1 ]]; then
                        echo \$bin >> test.list2
                        total=\$(( total + count - 2 ))
                        fi
                    done
                    chunk=\$(( total / ${chunk_count} ))
                    remain=\$chunk
                    part=0
                    for bin in `cat test.list2`; do
                        curl -F tikv_test/${ghprbActualCommit}\$bin=@\$bin ${FILE_SERVER2_URL}/upload
                        \$bin --list | head -n -2 | awk '{print substr(\$1, 1, length(\$1)-1)}' > cases
                        origin_count=`cat cases | wc -l`
                        count=\$origin_count
                        while [[ \$remain -lt \$count ]]; do
                        echo \$bin --test --exact `tail -n \$count cases | head -n \$remain` --nocapture >> test-chunk-\$part
                        part=\$(( part + 1 ))
                        count=\$(( count - remain ))
                        remain=\$chunk
                        done
                        if [[ \$count -gt 0 ]]; then
                        if [[ \$origin_count -eq \$count ]]; then
                            echo \$bin --test --nocapture >>test-chunk-\$part
                        else
                            echo \$bin --test --exact `tail -n \$count cases` --nocapture >> test-chunk-\$part
                        fi
                        remain=\$(( remain - count ))
                        fi
                        if [[ \$remain -eq 0 ]]; then
                        remain=\$chunk
                        part=\$(( part + 1 ))
                        fi
                    done
                    chmod a+x test-chunk-*
                    tar czf test-chunk.tar.gz test-chunk-*
                    curl -F tikv_test/${ghprbActualCommit}/test-chunk.tar.gz=@test-chunk.tar.gz ${FILE_SERVER2_URL}/upload
                    echo 1 > build_passed
                    curl -F tikv_test/${ghprbActualCommit}/build_passed=@build_passed ${FILE_SERVER2_URL}/upload
                    """
                }
            }
        }
    }

    def prepare = [:]
    prepare["Lint"] = {
        clippy()
    }
    prepare["Build"] = {
        build()
    }
    parallel prepare
}

stage('Test') {
    def run_test = { chunk_suffix ->
        node("${GO_TEST_SLAVE}") {
            dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    deleteDir()
                    retry(retryCount) {timeout(15) {
                        sh """
                        # set -o pipefail
                        uname -a
                        export RUSTFLAGS=-Dwarnings
                        export FAIL_POINT=1
                        export RUST_BACKTRACE=1
                        export MALLOC_CONF=prof:true,prof_active:false
                        mkdir -p target/debug
                        curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test-chunk.tar.gz
                        tar xf test-chunk.tar.gz
                        ls -la
                        if [[ ! -f test-chunk-${chunk_suffix} ]]; then
                            if [[ ${chunk_suffix} -eq ${chunk_count} ]]; then
                            exit
                            else
                            echo test-chunk-${chunk_suffix} not found
                            exit 1
                            fi
                        fi
                        for i in `cat test-chunk-${chunk_suffix} | cut -d ' ' -f 1 | sort -u`; do
                            curl -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/\$i --create-dirs;
                            chmod +x \$i;
                        done
                        CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 ./test-chunk-${chunk_suffix} 2>&1 | tee tests.out
                        chunk_count=`cat test-chunk-${chunk_suffix} | wc -l`
                        ok_count=`grep "test result: ok" tests.out | wc -l`
                        if [ "\$chunk_count" -eq "\$ok_count" ]; then
                            echo "test pass"
                        else
                            # test failed
                            status=1
                            printf "\n\n ===== cat target/my_test.log ===== \n"
                            cat target/my_test.log | cut -d ' ' -f 2-
                        fi
                        if grep 'core dumped' tests.out > /dev/null 2>&1
                        then
                            # there is a core dumped, which should not happen.
                            status=1
                            echo 'there is a core dumped, which should not happen'
                            gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                        fi
                        exit \$status
                        """
                    }}
                }
            }
        }
    }

    def tests = [:]
    for (int i = 0; i <= chunk_count; i ++) {
        tests["Part${i}"] = {
            run_test(i)
        }
    }

    parallel tests
}
currentBuild.result = "SUCCESS"
stage('Post-test') {
    node("${GO_BUILD_SLAVE}"){
        container("golang"){
            sh """
            echo "done" > done
            curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload
            """
        }
    }
}