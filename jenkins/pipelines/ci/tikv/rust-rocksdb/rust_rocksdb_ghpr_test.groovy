pullId = params.get("ghprbPullId")
commit = params.get("ghprbActualCommit")

toolchain = "https://raw.githubusercontent.com/tikv/tikv/master/rust-toolchain"
common_features = "encryption,portable"
x86_features = "jemalloc,sse"
arm_features = "jemalloc"

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

def checkout() {
    run_with_x86_pod {
        container("rust") {
            dir("rust-rocksdb") {
                deleteDir()
                checkout(changelog: false, poll: false, scm: [
                    $class: "GitSCM",
                    branches: [[name: 'master']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/tikv/rust-rocksdb.git',
                        refspec: '+refs/pull/*/head:refs/remotes/origin/pr/*'
                    ]],
                    extensions: [
                        [$class: 'PruneStaleBranch'],
                        [$class: 'CleanBeforeCheckout'],
                        [$class: 'SubmoduleOption', recursiveSubmodules: true],
                    ],
                ])
                sh """
                    # checkout git commit of the PR
                    git checkout -f ${commit}
                    git submodule update

                    # sync rust-toolchain with TiKV
                    curl ${toolchain} > ./rust-toolchain
                    cat ./rust-toolchain
                """
            }
            stash includes: "rust-rocksdb/**", name: "rust-rocksdb", useDefaultExcludes: false
        }
    }
}

def prepare() {
    stage("Prepare") {
        dir("rust-rocksdb") {
            deleteDir()
        }
        unstash "rust-rocksdb"
    }
}

def set_devtoolset = { use_gcc8 ->
    if (use_gcc8) {
        sh """
            source /opt/rh/devtoolset-8/enable
            g++ --version
        """
    }
}

def run_test = { features, use_gcc8 ->
    prepare()
    dir("rust-rocksdb") {
        stage("Build") {
            set_devtoolset(use_gcc8)
            sh """
                cargo build --features=${features}
            """
        }
        stage("Test") {
            set_devtoolset(use_gcc8)
            sh """
                RUST_BACKTRACE=1 cargo test --all --features=${features}
            """
        }
    }
}

def run_formatter = {
    run_with_x86_pod {
        prepare()
        dir("rust-rocksdb") {
            stage("Format Rust") {
                container("rust") {
                    sh """
                        cargo fmt --all -- --check
                    """
                }
            }
            stage("Format C++") {
                container("rust") {
                     sh """
                         source /opt/rh/llvm-toolset-7.0/enable
                         find librocksdb_sys/crocksdb/ -iname *.h -o -iname *.cc | xargs -L1 clang-format -i
                         if [[ \$(git diff) ]]; then
                             echo "Run scripts/format-diff.sh to format your code.";
                             exit 1;
                         fi;
                     """
                }
            }
        }
    }
}

stage("Checkout") {
    checkout()
}

parallel(
    test_x86_minimal: {
        def use_gcc8 = true
        run_with_x86_pod {
            container("rust") {
                run_test("", use_gcc8)
            }
        }
    },
    test_x86_all: {
        def use_gcc8 = true
        run_with_x86_pod {
            container("rust") {
                run_test(common_features + "," + x86_features, use_gcc8)
            }
        }
    },
    test_arm: {
        def use_gcc8 = true
        node("arm") {
            run_test(common_features + "," + arm_features, use_gcc8)
        }
    },
    formater: {
        run_formatter()
    },
)
