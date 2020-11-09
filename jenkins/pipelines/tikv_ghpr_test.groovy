def notRun = 1

def slackcolor = 'good'
def githash

def chunk_count = 20
def ci_chunks = []

ci_chunks.add("_0_ci_setup:")
ci_chunks.add("_1_check_system_requirement:")

def remove_last_str = { str ->
    return str.substring(0, str.length() - 1)
}

def match_item = { item, chunks ->
    for (int i = 0; i< chunks.size(); i++) {
        if (item == chunks[i]) {
            return true
        }
    }
    return false
}

def get_skip_str = { total_chunks, ci_chunks_list, test_chunks ->
    def persist_chunks = []
    persist_chunks = persist_chunks.plus(ci_chunks_list)
    for (int i = 0; i < test_chunks.size(); i++) {
        persist_chunks.add(test_chunks[i])
    }
    println persist_chunks
    def skipStr = ""
    for (int i = 0 ; i < total_chunks.size(); i++) {
        if (match_item(total_chunks[i], persist_chunks)) {
            continue
        }
        def trimStr = remove_last_str(total_chunks[i])
        skipStr = skipStr + " --skip ${trimStr}"
    }
    return skipStr
}

def readfile = { filename ->
    def file = readFile filename
    return file.split("\n")
}

def retryCount = 1
if (ghprbPullTitle != null && (ghprbPullTitle.find("DNM") != null || ghprbPullTitle.startsWith("WIP:"))) {
    retryCount = 1
}

try {
    stage("Pre-check") {
        if (!params.force){
            node("${GO_BUILD_SLAVE}"){
                container("golang"){
                    notRun = sh(returnStatus: true, script: """
                    if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/ci_check/${JOB_NAME}/${ghprbActualCommit}; then exit 0; else exit 1; fi
                    """)   
                }
            }
        }

        if (notRun == 0){
            println "the ${ghprbActualCommit} has been tested"
            throw new RuntimeException("hasBeenTested")
        }
    }
    stage("Prepare") {
        def clippy = {
            node("build_tikv") {
                def ws = pwd()
                // deleteDir()

                println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                println "[Debug Info] Workspace: ${ws}"

                def is_lint_passed = false
                container("rust") {
                    is_lint_passed = (sh(label: 'Try to skip linting', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/lint_passed') == 0)
                    println "Skip linting: ${is_lint_passed}"
                }

                if (!is_lint_passed) {
                    container("rust") {
                        dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                            // sh "chown -R 10000:10000 ./"
                            deleteDir()
                        }

                        if(!fileExists("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build/Makefile")) {
                            dir("/home/jenkins/agent/git/tikv") {
                                if (sh(returnStatus: true, label: 'Verify workspace', script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:tikv/tikv.git']]]
                            }
                            dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                                timeout(30){
                                    sh label: 'Download lint cache', script: """
                                    # set -o pipefail
                                    cp -R /home/jenkins/agent/git/tikv/. ./
                                    eval "du -ms *"
                                    rm -rf target
                                    if curl ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/target_lint.tar.lz4 | lz4 -d - | tar x; then
                                        set +o pipefail
                                    else
                                        if curl ${FILE_SERVER2_URL}/download/rust_cache/branch_${ghprbTargetBranch}/target_lint.tar.lz4 | lz4 -d - | tar x; then
                                            set +o pipefail
                                        fi
                                    fi
                                    eval "du -ms *"
                                    set -euo pipefail
                                    """
    
                                    sh returnStatus: true, label: 'Restore cargo dependencies mtime', script: """
                                    if [ -f "target/cargo-mtime.json" ]; then
                                        curl https://gist.githubusercontent.com/breeswish/8b3328fae820b9ac59fa227dd2ad75af/raw/c26c9d63cdad30b6e82e7d43c3b41bc4ef5ee78c/cargo-mtime.py -sSf | python - restore --meta-file target/cargo-mtime.json
                                    fi
                                    """
                                }
                            }
                        }


                        dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                            timeout(300) {
                                if (sh(returnStatus: true, label: 'Verify workspace', script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:tikv/tikv.git']]]
                                sh label: 'Checkout commit', script: 'git checkout -f ${ghprbActualCommit}'

                                // sh label: 'Reset mtime for better caching', script: """
                                // curl https://raw.githubusercontent.com/MestreLion/git-tools/cd87904e0b85d74b1d05f6acbd10553a0ceaceb0/git-restore-mtime-bare -sSf | python -
                                // """

                                sh label: 'Prepare dependencies', script: """
                                rustup override unset
                                rustc --version
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
                                make clippy || (echo Please fix the clippy error; exit 1)
                                """

                                sh label: 'Post-lint: Save lint status', script: """
                                echo 1 > lint_passed
                                curl -F tikv_test/${ghprbActualCommit}/lint_passed=@lint_passed ${FILE_SERVER2_URL}/upload
                                """

                                // sh label: 'Post-lint: Upload lint dependencies', script: """
                                // tar cf - -C \$CARGO_HOME git registry --format posix | lz4 -c > dep_lint.tar.lz4
                                // ls -la dep_lint.tar.lz4
                                // if ! curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/dep_lint.tar.lz4; then
                                //     curl -F rust_cache/${ghprbPullId}/dep_lint.tar.lz4=@dep_lint.tar.lz4 ${FILE_SERVER2_URL}/upload
                                // fi
                                // """

                                sh label: 'Post-lint: Save cargo dependencies mtime', script: """

                                if [ -f "target/cargo-mtime.json" ]; then
                                  curl https://gist.githubusercontent.com/breeswish/8b3328fae820b9ac59fa227dd2ad75af/raw/c26c9d63cdad30b6e82e7d43c3b41bc4ef5ee78c/cargo-mtime.py -sSf | python - save --meta-file target/cargo-mtime.json
                                fi
                                """

                                sh label: 'Post-lint: Upload lint cache', script: """
                                tar cf - target/debug/.fingerprint target/debug/build target/debug/deps target/debug/incremental target/cargo-mtime.json --format posix | lz4 -c > target_lint.tar.lz4
                                ls -la target_lint.tar.lz4
                                if ! curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/target_lint.tar.lz4; then
                                    curl -F rust_cache/${ghprbPullId}/target_lint.tar.lz4=@target_lint.tar.lz4 ${FILE_SERVER2_URL}/upload
                                fi
                                """
                                // master 分支阈值为 40G, 其他分支 20G
                                def threshold = 40000000
                                if (ghprbTargetBranch != "master") {
                                    threshold = 20000000
                                }
                                sh """
                                if [ `du -d 0 | cut -f 1` -ge ${threshold} ]; then
                                    cargo clean
                                fi
                                """
                            }
                        }
                    }
                }
            }
        }


        def build = {
            node("build_tikv") {
                def ws = pwd()
                // deleteDir()

                println "[Debug Info] Debug command: kubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                println "[Debug Info] Workspace: ${ws}"

                def is_artifact_existed = false
                container("rust") {
                    is_artifact_existed = (sh(label: 'Try to skip building test artifact', returnStatus: true, script: 'curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz') == 0)
                    println "Skip building test artifact: ${is_artifact_existed}"
                }

                if (!is_artifact_existed){
                    stage("Build") {
                        // prepare
                        container("rust") {
                            timeout(60) {
                                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                                    // sh "chown -R 10000:10000 ./"
                                    deleteDir()
                                }
    
                                if(!fileExists("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build/Makefile")) {
                                    dir("/home/jenkins/agent/git/tikv") {
                                        if (sh(returnStatus: true, label: 'Verify workspace', script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                            deleteDir()
                                        }
                                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:tikv/tikv.git']]]
                                    }
                                    dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                                        // sh label: 'Download build dependencies', script: """
                                        // if curl ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/dep.tar.lz4 | lz4 -d - | tar x -C \$CARGO_HOME --warning=no-timestamp; then
                                        //     set +o pipefail
                                        // else
                                        //     if curl ${FILE_SERVER2_URL}/download/rust_cache/branch_${ghprbTargetBranch}/dep.tar.lz4 | lz4 -d - | tar x -C \$CARGO_HOME --warning=no-timestamp; then
                                        //         set +o pipefail
                                        //     fi
                                        // fi
                                        // """
    
                                        sh label: 'Download build cache', script: """
                                        set -o pipefail
                                        cp -R /home/jenkins/agent/git/tikv/. ./
                                        eval "du -ms *"
                                        rm -rf target
    
                                        if curl ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/target.tar.lz4 | lz4 -d - | tar x; then
                                            set +o pipefail
                                        else
                                            if curl ${FILE_SERVER2_URL}/download/rust_cache/branch_${ghprbTargetBranch}/target.tar.lz4 | lz4 -d - | tar x; then
                                                set +o pipefail
                                            fi
                                        fi
                                        eval "du -ms *"
                                        set -euo pipefail
                                        """
    
                                        sh returnStatus: true, label: 'Restore cargo dependencies mtime', script: """
                                        if [ -f "target/cargo-mtime.json" ]; then
                                            curl https://gist.githubusercontent.com/breeswish/8b3328fae820b9ac59fa227dd2ad75af/raw/c26c9d63cdad30b6e82e7d43c3b41bc4ef5ee78c/cargo-mtime.py -sSf | python - restore --meta-file target/cargo-mtime.json
                                        fi
                                        """
                                    }
                                }
    
                                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                                    timeout(60) {
                                        // sh "chown -R 10000:10000 ./"
                                        if (sh(returnStatus: true, label: 'Verify workspace', script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                            deleteDir()
                                        }
                                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:tikv/tikv.git']]]
                                        sh label: 'Checkout commit', script: 'git checkout -f ${ghprbActualCommit}'
    
                                        def toolchain= sh(returnStdout: true, script: "cat rust-toolchain").trim()
                                        println "[Debug info]toolchain: ${toolchain}"
    
                                        // sh label: 'Reset mtime for better caching', script: """
                                        // curl https://raw.githubusercontent.com/MestreLion/git-tools/cd87904e0b85d74b1d05f6acbd10553a0ceaceb0/git-restore-mtime-bare -sSf | python -
                                        // """
    
                                        sh label: 'Prepare dependencies', script: """
                                        rustup override unset
                                        rustc --version
                                        """
                                    
    
                                        sh label: 'Build test artifact', script: """
                                        export RUSTFLAGS=-Dwarnings
                                        export FAIL_POINT=1
                                        export ROCKSDB_SYS_SSE=1
                                        export RUST_BACKTRACE=1
                                        export LOG_LEVEL=INFO
                                        # export CARGO_LOG=cargo::core::compiler::fingerprint=debug
    
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
                                        cat test.json| jq -r "select(.profile.test == true) | .filenames[]" > test.list
                                        for i in `cat test.list`;do curl -F tikv_test/${ghprbActualCommit}\$i=@\$i ${FILE_SERVER2_URL}/upload;done
                                        cat test.list|grep -v codec_bytes | grep -v failpoints | grep -v integrations | grep -v debug/tikv- > test.normal
                                        split test.normal -n r/8 test_chunk_ -a 1 --numeric-suffixes=1
                                        for i in `cat test.list| grep integrations`;do \$i --list | awk '{print \$1}' | grep ':' >> integrations.list;done
                                        split integrations.list -n r/${chunk_count} integrations_chunk_ -a 2 --numeric-suffixes=2
                                        """
    
                                        // sh label: 'Post-build: Upload build dependencies', script: """
                                        // tar cf - -C \$CARGO_HOME git registry --format posix | lz4 -c > dep.tar.lz4
                                        // ls -la dep.tar.lz4
                                        // if ! curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/dep.tar.lz4; then
                                        //     curl -F rust_cache/${ghprbPullId}/dep.tar.lz4=@dep.tar.lz4 ${FILE_SERVER2_URL}/upload
                                        // fi
                                        // """
    
                                        sh label: 'Post-build: Save cargo dependencies mtime', script: """
                                        curl https://gist.githubusercontent.com/breeswish/8b3328fae820b9ac59fa227dd2ad75af/raw/c26c9d63cdad30b6e82e7d43c3b41bc4ef5ee78c/cargo-mtime.py -sSf | python - save --meta-file target/cargo-mtime.json
                                        """
    
                                        sh label: 'Post-build: Upload build cache', script: """
                                        tar cf - target/debug/.fingerprint target/debug/build target/debug/deps target/debug/incremental target/cargo-mtime.json --format posix | lz4 -c > target.tar.lz4
                                        ls -la target.tar.lz4
                                        if ! curl --output /dev/null --silent --head --fail ${FILE_SERVER2_URL}/download/rust_cache/${ghprbPullId}/target.tar.lz4; then
                                            curl -F rust_cache/${ghprbPullId}/target.tar.lz4=@target.tar.lz4 ${FILE_SERVER2_URL}/upload
                                        fi
                                        """
    
                                        sh label: 'Post-build: Upload test data files', script: """
                                        rm -rf target.tar.lz4 dep.tar.lz4 target
                                        cp -R /rust/toolchains/${toolchain}-x86_64-unknown-linux-gnu/lib .
                                        tar --exclude=test_stash.gz -czf test_stash.gz *
                                        curl -F tikv_test/${ghprbActualCommit}/test_stash.gz=@test_stash.gz ${FILE_SERVER2_URL}/upload
                                        """
                                    }
                                    // sh "chown -R 10000:10000 ./"
                                    deleteDir()
                                }
                            }
                        }
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
                            curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz
                            tar xf test_stash.gz
                            ls -la
                            for i in `cat test_chunk_${chunk_suffix}`;do
                                curl -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}\$i --create-dirs;
                                chmod +x \$i;
                                # ls -althr \$(pwd)/lib/*
                                export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:\$(pwd)/lib
                                CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 \$i --nocapture;
                            done 2>&1 | tee tests.out
                            curl -O ${FILE_SERVER2_URL}/download/script/filter_tikv.py
                            chunk_count=`cat test_chunk_${chunk_suffix} | wc -l`
                            ok_count=`grep "test result: ok" tests.out | wc -l`
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                              echo "test pass"
                            else
                              # test failed
                              status=1
                              curl -O http://fileserver.pingcap.net/download/builds/pingcap/ee/print-logs.py
                              # python print-logs.py target/my_test.log
                              printf "\n\n ===== cat target/my_test.log ===== \n"
                              cat target/my_test.log
                            fi
                            if cat tests.out | grep 'core dumped' > /dev/null 2>&1
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

        def run_exclude_5 = { componment, exclude1, exclude2, exclude3, exclude4, exclude5 ->
            node("${GO_TEST_SLAVE}") {
                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                    container("golang") {

                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        deleteDir()
                        retry(retryCount) {
                            timeout(30) {
                            sh """
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:\$(pwd)/lib
                            curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz
                            tar xf test_stash.gz
                            ls -la
                            mkdir -p target/debug
                            for i in `cat test.list| grep -E 'debug/([^/]*/)*${componment}'`;do curl --create-dirs -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}\$i;chmod +x \$i;CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 \$i --skip ${exclude1} --skip ${exclude2} --skip ${exclude3} --skip ${exclude4} --skip ${exclude5} --nocapture;done 2>&1 | tee tests.out
                            curl -O ${FILE_SERVER2_URL}/download/script/filter_tikv.py
                            chunk_count=`cat test.list | grep -E 'debug/([^/]*/)*${componment}' | wc -l`
                            ok_count=`grep "test result: ok" tests.out | wc -l`
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                              echo "test pass"
                            else
                              # test failed
                              status=1
                              curl -O http://fileserver.pingcap.net/download/builds/pingcap/ee/print-logs.py
                              # python print-logs.py target/my_test.log
                              printf "\n\n ===== cat target/my_test.log ===== \n"
                              cat target/my_test.log
                            fi
                            if cat tests.out | grep 'core dumped' > /dev/null 2>&1
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

        def run_exclude_3 = { componment, exclude1, exclude2, exclude3 ->
            node("${GO_TEST_SLAVE}") {
                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                    container("golang") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                        deleteDir()
                        try {
                           retry(retryCount) {timeout(30) {
                            sh """
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:\$(pwd)/lib
                            curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz
                            tar xf test_stash.gz
                            ls -la
                            mkdir -p target/debug
                            for i in `cat test.list| grep -E 'debug/([^/]*/)*${componment}'`;do curl --create-dirs -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}\$i;chmod +x \$i;CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 \$i --skip ${exclude1} --skip ${exclude2} --skip ${exclude3} --nocapture;done 2>&1 | tee tests.out
                            curl -O ${FILE_SERVER2_URL}/download/script/filter_tikv.py
                            chunk_count=`cat test.list | grep -E 'debug/([^/]*/)*${componment}' | wc -l`
                            ok_count=`grep "test result: ok" tests.out | wc -l`
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                              echo "test pass"
                            else
                              # test failed
                              status=1
                              curl -O http://fileserver.pingcap.net/download/builds/pingcap/ee/print-logs.py
                              # python print-logs.py target/my_test.log
                              printf "\n\n ===== cat target/my_test.log ===== \n"
                              cat target/my_test.log
                            fi
                            if cat tests.out | grep 'core dumped' > /dev/null 2>&1
                            then
                                # there is a core dumped, which should not happen.
                                status=1
                                echo 'there is a core dumped, which should not happen'
								gdb -c core.* -batch -ex "info threads" -ex "thread apply all bt"
                            fi
                            exit \$status
                            """
                           }}
                        } catch (Exception e) {
                               sh """
                                for case in `cat tests.out | grep 'has been running for over 60 seconds' | awk '{print \$2}' | awk -F':' '{print \$NF}'`; do
                                echo find fail cases: \$case
                                grep \$case target/my_test.log | cut -d ' ' -f 2- | head -n 5000
                                # there is a thread panic, which should not happen.
                                status=1
                                echo
                                done
                               """
                               throw e
                        }
                    }
                }
            }
        }


        def run_root_exact = { componment, exclude ->
            node("${GO_TEST_SLAVE}") {
                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                    container("golang") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                        println "pwd: ${pwd()}"

                        deleteDir()
                        retry(retryCount) { timeout(30) {
                            sh """
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:\$(pwd)/lib
                            export MALLOC_CONF=prof:true,prof_active:false
                            sudo sysctl -w net.ipv4.ip_local_port_range='10000 30000'
                            curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz
                            tar xf test_stash.gz
                            ls -la
                            mkdir -p target/debug
                            for i in `cat test.list| grep -E 'debug/([^/]*/)*${componment}'`;do curl --create-dirs -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}\$i;chmod +x \$i;CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 \$i ${exclude} --nocapture;done 2>&1 | tee tests.out
                            curl -O ${FILE_SERVER2_URL}/download/script/filter_tikv.py
                            chunk_count=`cat test.list | grep -E 'debug/([^/]*/)*${componment}' | wc -l`
                            ok_count=`grep "test result: ok" tests.out | wc -l`
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                              echo "test pass"
                            else
                              # test failed
                              status=1
                              curl -O http://fileserver.pingcap.net/download/builds/pingcap/ee/print-logs.py
                              # python print-logs.py target/my_test.log
                              printf "\n\n ===== cat target/my_test.log =====\n"
                              cat target/my_test.log
                            fi
                            if cat tests.out | grep 'core dumped' > /dev/null 2>&1
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

        def run_integration_chunk = { chunk_index ->
            node("${GO_TEST_SLAVE}") {
                dir("/home/jenkins/agent/tikv-${ghprbTargetBranch}/build") {
                    container("golang") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                        deleteDir()
                        timeout(30) {
                            sh """
                            curl -O ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}/test_stash.gz
                            tar xf test_stash.gz
                            ls -la
                            """
                        }
                        def total_chunks = readfile("integrations.list")
                        def run_chunks = []
                        if (chunk_index < 10) {
                            run_chunks = readfile("integrations_chunk_0${chunk_index}")
                        } else {
                            run_chunks = readfile("integrations_chunk_${chunk_index}")
                        }
                        def skipStr = get_skip_str(total_chunks,ci_chunks, run_chunks)
                        println skipStr
                        retry(retryCount) {timeout(60) {
                            sh """
                            # set -o pipefail
                            export RUSTFLAGS=-Dwarnings
                            export FAIL_POINT=1
                            export RUST_BACKTRACE=1
                            export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:\$(pwd)/lib
                            sudo sysctl -w net.ipv4.ip_local_port_range='10000 30000'
                            mkdir -p target/debug
                            for i in `cat test.list| grep integrations`;do 
                                curl -o \$i ${FILE_SERVER2_URL}/download/tikv_test/${ghprbActualCommit}\$i --create-dirs;
                                chmod +x \$i;
                                CI=1 LOG_FILE=target/my_test.log RUST_TEST_THREADS=1 RUST_BACKTRACE=1 \$i ${skipStr} --nocapture;
                            done 2>&1 | tee tests.out
                            curl -O ${FILE_SERVER2_URL}/download/script/filter_tikv.py
                            chunk_count=`cat test.list | grep integrations | wc -l`
                            ok_count=`grep "test result: ok" tests.out | wc -l`
                            if [ "\$chunk_count" -eq "\$ok_count" ]; then
                              echo "test pass"
                            else
                              # test failed
                              status=1
                              curl -O http://fileserver.pingcap.net/download/builds/pingcap/ee/print-logs.py
                              # python print-logs.py target/my_test.log
                              printf "\n\n ===== cat target/my_test.log ===== \n"
                              cat target/my_test.log
                            fi
                            if cat tests.out | grep 'core dumped' > /dev/null 2>&1
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

        tests["Components Part1"] = {
            run_test(1)
        }

        tests["Components Part2"] = {
            run_test(2)
        }

        tests["Components Part3"] = {
            run_test(3)
        }

        tests["Components Part4"] = {
            run_test(4)
        }

        tests["Components Part5"] = {
            run_test(5)
        }

        tests["Components Part6"] = {
            run_test(6)
        }

        tests["Components Part7"] = {
            run_test(7)
        }

        tests["Components Part8"] = {
            run_test(8)
        }

        tests["Library Part1"] = {
            run_exclude_5("tikv-", "coprocessor::endpoint::", "raftstore::", "storage::mvcc::reader::", "storage::mvcc::txn::", "server::")
        }

        tests["Library Part2"] = {
            run_root_exact("tikv-", "coprocessor::endpoint::")
        }

        tests["Library Part3"] = {
            run_root_exact("tikv-", "raftstore::")
        }

        tests["Library Part4"] = {
            run_root_exact("tikv-", "storage::mvcc::reader::")
        }

        tests["Library Part5"] = {
            run_root_exact("tikv-", "storage::mvcc::txn::")
        }

        tests["Library Part6"] = {
            run_root_exact("tikv-", "server::")
        }

        tests["Failpoints Part1"] = {
            run_exclude_3("failpoints", "cases::test_storage::", "cases::test_snap::", "cases::test_stale")
        }

        tests["Failpoints Part2"] = {
            run_root_exact("failpoints", "cases::test_storage::")
        }

        tests["Failpoints Part3"] = {
            run_root_exact("failpoints", "cases::test_snap::")
        }

        tests["Failpoints Part4"] = {
            run_root_exact("failpoints", "cases::test_stale")
        }

        for (int i = 2; i < chunk_count + 2; i ++) {
            def j = i-1
            def k = i
            tests["Integration Part${j}"] = {
                run_integration_chunk(k)
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
} catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
    currentBuild.result = "ABORTED"
} catch (Exception e) {
    errorDescription = e.getMessage()
    if (errorDescription == "hasBeenTested") {
        currentBuild.result = 'SUCCESS'
    } else {
        currentBuild.result = "FAILURE"
        slackcolor = 'danger'
        echo "${e}"
    }
}

stage('Summary') {
    echo "Send slack here ..."
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
            "${ghprbPullLink}" + "\n" +
            "${ghprbPullDescription}" + "\n" +
            "Unit Test Result: `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration} mins` " + "\n" +
            "${env.RUN_DISPLAY_URL}"

    if (currentBuild.result == "FAILURE") {
        slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}
