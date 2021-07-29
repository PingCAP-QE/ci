pullId = params.get("ghprbPullId")
commit = params.get("ghprbActualCommit")

rocksdbBranch = "6.4.tikv"
compression = "-DWITH_SNAPPY=ON -DWITH_LZ4=ON -DWITH_ZLIB=ON -DWITH_ZSTD=ON"

def checkout = {
    node("build") {
        dir("titan") {
            deleteDir()
            checkout(changelog: false, poll: false, scm: [
                $class: "GitSCM",
                branches: [[name: 'master']],
                userRemoteConfigs: [[
                    url: 'https://github.com/tikv/titan.git',
                    refspec: '+refs/pull/*/head:refs/remotes/origin/pr/*'
                ]],
                extensions: [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                ],
            ])
            sh """
                # checkout git commit of the PR
                git checkout -f ${commit}
            """
        }
        dir("rocksdb") {
            deleteDir()
            checkout(changelog: false, poll: false, scm: [
                $class: "GitSCM",
                branches: [[name: rocksdbBranch]],
                userRemoteConfigs: [[
                    url: 'https://github.com/tikv/rocksdb.git',
                ]],
                extensions: [
                    [$class: 'PruneStaleBranch'],
                    [$class: 'CleanBeforeCheckout'],
                ],
            ])
        }
        stash includes: "titan/**", name: "titan", useDefaultExcludes: false
        stash includes: "rocksdb/**", name: "rocksdb"
    }
}

def prepare() {
    stage("Prepare") {
        dir("tmp_dir") {
            deleteDir()
        }
        dir("titan") {
            deleteDir()
        }
        dir("rocksdb") {
            deleteDir()
        }
        unstash "titan"
        unstash "rocksdb"
    }
}

def run_test = { build_type, sanitizer, use_gcc8 ->
    prepare()
    dir("titan") {
        stage("Build") {
            def rocksdb_dir = "-DROCKSDB_DIR=../rocksdb"
            def build_opt = "-DCMAKE_BUILD_TYPE=Debug"
            if (build_type != "") {
                build_opt = "-DCMAKE_BUILD_TYPE=" + build_type
            }
            def sanitizer_opt = ""
            def tools_opt = ""
            if (sanitizer != "") {
                sanitizer_opt = "-DWITH_" + sanitizer + "=ON"
                tools_opt = "-DWITH_TITAN_TOOLS=OFF"
            }
            def devtoolset = ""
            if (use_gcc8) {
                devtoolset = "source /opt/rh/devtoolset-8/enable"
            }
            sh """
                ${devtoolset}
                g++ --version
                cmake . -L ${rocksdb_dir} ${compression} ${build_opt} ${sanitizer_opt} ${tools_opt}
                VERBOSE=1 make -j
            """
        }
        if (build_type == "") {
            stage("Test") {
                // In jenkins, ASAN test is failing with leak from __cxa_thread_atexit detected,
                // which is probably false-positive. Disabling leak detection for now.
                // https://github.com/facebook/rocksdb/issues/5931
                sh """
                    TEST_TMPDIR=./tmp_dir ASAN_OPTIONS=detect_leaks=0 ctest --verbose -R titan
                """
            }
        }
    }
}

def run_formatter = {
    prepare()
    dir("titan") {
        stage("Format") {
            sh """
                source /opt/rh/llvm-toolset-7.0/enable
                find . -iname *.h -o -iname *.cc | xargs -L1 clang-format -style=google -i
                if [[ \$(git diff) ]]; then
                    echo "Run scripts/format-diff.sh to format your code.";
                    exit 1;
                fi;
            """
        }
    }
}

stage("Checkout") {
    checkout()
}

parallel(
    test: {
        def use_gcc8 = true
        node("build") {
            container("rust") {
                run_test("", "", use_gcc8)
            }
        }
    },
    test_asan: {
        def use_gcc8 = true
        node("build") {
            container("rust") {
                run_test("", "ASAN", use_gcc8)
            }
        }
    },
    test_tsan: {
        def use_gcc8 = true
        node("build") {
            container("rust") {
                run_test("", "TSAN", use_gcc8)
            }
        }
    },
    test_ubsan: {
        def use_gcc8 = true
        node("build") {
            container("rust") {
                run_test("", "UBSAN", use_gcc8)
            }
        }
    },
    test_mac: {
        def use_gcc8 = false
        node("mac-i7") {
            run_test("", "ASAN", use_gcc8)
        }
    },
    test_arm: {
        def use_gcc8 = true
        node("arm") {
            run_test("", "", use_gcc8)
        }
    },
    release: {
        def use_gcc8 = true
        node("build") {
            container("rust") {
                run_test("Release", "", use_gcc8)
            }
        }
    },
    format: {
        node("build") {
            container("rust") {
                run_formatter()
            }
        }
    },
)
