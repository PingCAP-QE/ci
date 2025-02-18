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
                        resourceRequestCpu: '16000m', resourceRequestMemory: '32Gi',
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

def test = { extra ->
    stage("Test") {
        dir("rocksdb") {
            deleteDir()
        }
        unstash "rocksdb"
        dir("rocksdb") {
            sh """
                echo using gcc 8
                source /opt/rh/devtoolset-8/enable
                TEST_TMPDIR=/home/jenkins/tmp_dir LIB_MODE=static V=1 ${extra} make J=32 -j32 check
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
            test("")
        }
    },
    x86: {
        parallel(
            basic: {
                run_with_x86_pod {
                    container("rust") {
                        test("")
                    }
                }
            },
            encrypted_env: {
                run_with_x86_pod {
                    container("rust") {
                        test("ENCRYPTED_ENV=1")
                    }
                }
            }
        )
    },
)