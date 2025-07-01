def pullId = params.get("ghprbPullId")
def commit = params.get("ghprbActualCommit")

def run_with_x86_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tikv"
    def rust_image = "hub.pingcap.net/jenkins/centos7_golang-1.13_rust:latest"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'rust', alwaysPullImage: true,
                        image: rust_image, ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        command: '/bin/sh -c', args: 'cat'
                    )
            ],
            volumes: [
                    // TODO use s3 cache instead of nfs
                    nfsVolume(mountPath: '/rust/registry/cache', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/rust/registry/cache', readOnly: false),
                    nfsVolume(mountPath: '/rust/registry/index', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/rust/registry/index', readOnly: false),
                    nfsVolume(mountPath: '/rust/git/db', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/rust/git/db', readOnly: false),
                    nfsVolume(mountPath: '/rust/git/checkouts', serverAddress: "${NFS_SERVER_ADDRESS}",
                            serverPath: '/data/nvme1n1/nfs/rust/git/checkouts', readOnly: false),
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false),
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            println "rust image: ${rust_image}"
            body()
        }
    }
}


def checkout = {
    run_with_x86_pod {
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
                echo using gcc 8
                source /opt/rh/devtoolset-8/enable
                LIB_MODE=static V=1 make ${target} -j 3
            """
        }
        if (do_cache) {
            stash includes: "rocksdb/**", name: "rocksdb_build"
        }
    }
}

def test = { start, end, extra, do_cache ->
    stage("Test") {
        if (do_cache) {
            dir("rocksdb") {
                deleteDir()
            }
            unstash "rocksdb_build"
        }
        dir("rocksdb") {
            sh """
                echo using gcc 8
                source /opt/rh/devtoolset-8/enable
                export TEST_TMPDIR=/home/jenkins/tmp_dir
                export ROCKSDBTESTS_START=${start}
                export ROCKSDBTESTS_END=${end}
                LIB_MODE=static V=1 ${extra} make all_but_some_tests check_some -j 3
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
            build("all", do_cache)
            test("", "db_block_cache_test", "", do_cache)
        }
    },
    /*
    mac: {
        node("mac-i7") {
            def do_cache = false
            build("all", do_cache)
            test("", "db_block_cache_test", "", do_cache)
        }
    },
    */
    x86: {
        def do_cache = true
        run_with_x86_pod {
            container("rust") {
                build("librocksdb_debug.a", do_cache)
            }
        }
        parallel(
            platform_dependent: {
                run_with_x86_pod {
                    container("rust") {
                        test("", "db_block_cache_test", "", do_cache)
                    }
                }
            },
            group1: {
                run_with_x86_pod {
                    container("rust") {
                        test("db_block_cache_test", "full_filter_block_test", "", do_cache)
                    }
                }
            },
            group2: {
                run_with_x86_pod {
                    container("rust") {
                        test("full_filter_block_test", "write_batch_with_index_test", "", do_cache)
                    }
                }
            },
            group3: {
                run_with_x86_pod {
                    container("rust") {
                        test("write_batch_with_index_test", "write_prepared_transaction_test", "", do_cache)
                    }
                }
            },
            group4: {
                run_with_x86_pod {
                    container("rust") {
                        test("write_prepared_transaction_test", "", "", do_cache)
                    }
                }
            },
            encrypted_env: {
                run_with_x86_pod {
                    container("rust") {
                        test("", "db_block_cache_test", "ENCRYPTED_ENV=1", do_cache)
                    }
                }
            },
        )
    },
    x86_release: {
        run_with_x86_pod {
            container("rust") {
                def do_cache = false
                build("release", do_cache)
            }
        }
    },
)
