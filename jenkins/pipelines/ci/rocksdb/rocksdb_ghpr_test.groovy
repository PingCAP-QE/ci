def pullId = params.get("ghprbPullId")
def commit = params.get("ghprbActualCommit")

def checkout = {
    node("build") {
        dir("rocksdb") {
            deleteDir()
            checkout(changelog: false, poll: false, scm: [
                $class: "GitSCM",
                branches: [[name: '6.4.tikv']],
                userRemoteConfigs: [[
                    url: 'https://github.com/tikv/rocksdb.git',
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
        stash includes: "rocksdb/**", name: "rocksdb", useDefaultExcludes: false
    }
}

def build = { target, do_cache ->
    stage("Build") {
        dir("rocksdb") {
            deleteDir()
        }
        unstash "rocksdb"
        dir("rocksdb") {
            sh """
                V=1 make ${target} -j
            """
        }
        if (do_cache) {
            stash includes: "rocksdb/**", name: "rocksdb_build"
        }
    }
}

def test = { start, end, extra, do_cache, use_tmp ->
    stage("Test") {
        if (do_cache) {
            dir("rocksdb") {
                deleteDir()
            }
            unstash "rocksdb_build"
        }
        dir("rocksdb") {
            // Physical hosts are used for ARM and MAC platform.
            // We need to specify a temporary directory for testing and clean it up.
            // However, setting tmporary test directory fail some RocksDB tests.
            def export_test_tmpdir = ""
            if (use_tmp) {
                export_test_tmpdir = "export TEST_TMPDIR=./tmp_dir"
            }
            sh """
                ${export_test_tmpdir}
                export ROCKSDBTESTS_START=${start}
                export ROCKSDBTESTS_END=${end}
                V=1 ${extra} make all_but_some_tests check_some -j
            """
        }
    }
}

stage("Checkout") {
    checkout()
}

parallel(
    arm: {
        node("arm") {
            def do_cache = false
            def use_tmp = true
            build("all", do_cache)
            test("", "db_block_cache_test", "", do_cache, use_tmp)
        }
    },
    mac: {
        node("mac-i7") {
            def do_cache = false
            build("all", do_cache)
            test("", "db_block_cache_test", "", do_cache)
        }
    },
    x86: {
        def do_cache = true
        def use_tmp = false
        node("build") {
            container("rust") {
                build("librocksdb_debug.a", do_cache)
            }
        }
        parallel(
            platform_dependent: {
                node("build") {
                    container("rust") {
                        test("", "db_block_cache_test", "", do_cache, use_tmp)
                    }
                }
            },
            group1: {
                node("build") {
                    container("rust") {
                        test("db_block_cache_test", "full_filter_block_test", "", do_cache, use_tmp)
                    }
                }
            },
            group2: {
                node("build") {
                    container("rust") {
                        test("full_filter_block_test", "write_batch_with_index_test", "", do_cache, use_tmp)
                    }
                }
            },
            group3: {
                node("build") {
                    container("rust") {
                        test("write_batch_with_index_test", "write_prepared_transaction_test", "", do_cache, use_tmp)
                    }
                }
            },
            group4: {
                node("build") {
                    container("rust") {
                        test("write_prepared_transaction_test", "", "", do_cache, use_tmp)
                    }
                }
            },
            encrypted_env: {
                node("build") {
                    container("rust") {
                        test("", "db_block_cache_test", "ENCRYPTED_ENV=1", do_cache, use_tmp)
                    }
                }
            },
        )
    },
    x86_release: {
        node("build") {
            container("rust") {
                def do_cache = false
                build("release", do_cache)
            }
        }
    },
)
