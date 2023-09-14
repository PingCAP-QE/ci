// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_unit-test.yaml'
// final REFS = readJSON(text: params.JOB_SPEC).refs
final dependency_dir = "/home/jenkins/agent/dependency"
Boolean proxy_cache_ready = false
String proxy_commit_hash = null


podYaml = """
apiVersion: v1
kind: Pod
spec:
  securityContext:
    fsGroup: 1000
  containers:
    - name: runner
      image: "hub.pingcap.net/tiflash/tiflash-llvm-base:amd64"
      command:
        - "/bin/bash"
        - "-c"
        - "cat"
      tty: true
      resources:
        requests:
          memory: 32Gi
          cpu: "12"
        limits:
          memory: 32Gi
          cpu: "12"
      volumeMounts:
      - mountPath: "/home/jenkins/agent/rust"
        name: "volume-0"
        readOnly: false
      - mountPath: "/home/jenkins/agent/ccache"
        name: "volume-1"
        readOnly: false
      - mountPath: "/home/jenkins/agent/dependency"
        name: "volume-2"
        readOnly: false
      - mountPath: "/home/jenkins/agent/ci-cached-code-daily"
        name: "volume-4"
        readOnly: false
      - mountPath: "/home/jenkins/agent/proxy-cache"
        name: "volume-5"
        readOnly: false
      - mountPath: "/tmp"
        name: "volume-6"
        readOnly: false
      - mountPath: "/tmp-memfs"
        name: "volume-7"
        readOnly: false
    - name: net-tool
      image: wbitt/network-multitool
      tty: true
      resources:
        limits:
          memory: 128Mi
          cpu: 100m
    - name: report
      image: hub.pingcap.net/jenkins/python3-requests:latest
      tty: true
      resources:
        limits:
          memory: 256Mi
          cpu: 100m
  volumes:
    - name: "volume-0"
      nfs:
        path: "/data/nvme1n1/nfs/tiflash/rust"
        readOnly: false
        server: "10.2.12.82"
    - name: "volume-2"
      nfs:
        path: "/data/nvme1n1/nfs/tiflash/dependency"
        readOnly: true
        server: "10.2.12.82"
    - name: "volume-1"
      nfs:
        path: "/data/nvme1n1/nfs/tiflash/ccache"
        readOnly: false
        server: "10.2.12.82"
    - name: "volume-4"
      nfs:
        path: "/data/nvme1n1/nfs/git"
        readOnly: true
        server: "10.2.12.82"
    - name: "volume-5"
      nfs:
        path: "/data/nvme1n1/nfs/tiflash/proxy-cache"
        readOnly: true
        server: "10.2.12.82"
    - name: "volume-6"
      emptyDir: {}
    - name: "volume-7"
      emptyDir:
        medium: Memory
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
          - matchExpressions:
              - key: kubernetes.io/arch
                operator: In
                values:
                  - amd64
              - key: ci-nvme-high-performance
                operator: In
                values:
                  - "true"
"""


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            // yamlFile POD_TEMPLATE_FILE
            yaml podYaml
            defaultContainer 'runner'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 50, unit: 'MINUTES')
        parallelsAlwaysFailFast()
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
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                dir("tiflash") {
                    // TODO: how to cache git repo 
                    // contrib contains submodule, submodule cache about 2.5G
                    // git repo not include submodule cache about 1.5G
                    // cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {

                    // }
                        retry(2) {
                            // script {
                            //     prow.checkoutRefs(REFS, withSubmodule=true)
                            // }
                            script {
                                sh """
                                cp -R /home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz ./
                                tar -xzf /home/jenkins/agent/ci-cached-code-daily/src-tics.tar.gz --strip-components=1
                                rm -f src-tics.tar.gz
                                git reset --hard
                                git clean -ffdx
                                # git clone https://github.com/pingcap/tiflash.git .
                                git status
                                git pull origin master
                                git pull
                                git submodule update --init --recursive
                                git status
                                """
                                dir("contrib/tiflash-proxy") {
                                    proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                    println "proxy_commit_hash: ${proxy_commit_hash}"
                                }
                            } 
                        }
                }
            }
        }
        stage("Prepare tools") {
                // TODO: need to simplify this part
                // all tools should be pre-install in docker image
                parallel {
                stage("Ccache") {
                    steps {
                        sh label: "install ccache", script: """
                            if ! command -v ccache &> /dev/null; then
                                echo "ccache not found! Installing..."
                                rpm -Uvh '${dependency_dir}/ccache.x86_64.rpm'
                            else
                                echo "ccache is already installed!"
                            fi
                        """
                    }
                }
                stage("Cmake") {
                    steps { 
                        sh label: "install cmake3", script: """
                            if ! command -v cmake &> /dev/null; then
                                echo "cmake not found! Installing..."
                                sh ${dependency_dir}/cmake-3.22.3-linux-x86_64.sh --prefix=/usr --skip-license --exclude-subdir
                            else
                                echo "cmake is already installed!"
                            fi
                        """
                    }
                }
                stage("Clang-Format") {
                    steps {
                        sh label: "install clang-format", script: """
                            if ! command -v clang-format &> /dev/null; then
                                echo "clang-format not found! Installing..."
                                cp '${dependency_dir}/clang-format-12' '/usr/local/bin/clang-format'
                                chmod +x '/usr/local/bin/clang-format'
                            else
                                echo "clang-format is already installed!"
                            fi
                        """
                    }
                }
                stage("Clang-Format-15") {
                    steps { 
                        sh label: "install clang-format-15", script: """
                            if ! command -v clang-format-15 &> /dev/null; then
                                echo "clang-format-15 not found! Installing..."
                                cp '${dependency_dir}/clang-format-15' '/usr/local/bin/clang-format-15'
                                chmod +x '/usr/local/bin/clang-format-15'
                            else
                                echo "clang-format-15 is already installed!"
                            fi
                        """
                    }
                }
                stage( "Clang-Tidy") {
                    steps { 
                        sh label: "install clang-tidy", script: """
                            if ! command -v clang-tidy &> /dev/null; then
                                echo "clang-tidy not found! Installing..."
                                cp '${dependency_dir}/clang-tidy-12' '/usr/local/bin/clang-tidy'
                                chmod +x '/usr/local/bin/clang-tidy'
                                cp '${dependency_dir}/lib64-clang-12-include.tar.gz' '/tmp/lib64-clang-12-include.tar.gz'
                                cd /tmp && tar zxf lib64-clang-12-include.tar.gz
                            else
                                echo "clang-tidy is already installed!"
                            fi
                        """
                    }
                }
                stage("Coverage") {
                    steps {
                        sh label: "install gcovr", script: """
                            if ! command -v gcovr &> /dev/null; then
                                echo "lcov not found! Installing..."
                                cp '${dependency_dir}/gcovr.tar' '/tmp/'
                                cd /tmp
                                tar xvf gcovr.tar && rm -rf gcovr.tar
                                ln -sf /tmp/gcovr/gcovr /usr/bin/gcovr
                            else
                                echo "lcov is already installed!"
                            fi
                        """
                    }
                }
                }
        }
        stage("Prepare Cache") {
            parallel {
                stage("Ccache") {
                    steps {
                    script { 
                        // TODO: need adjust "master" against different target branch
                        def ccache_tag = "pagetools-tests-amd64-linux-llvm-debug-master-failpoints"
                        def ccache_source = "/home/jenkins/agent/ccache/${ccache_tag}.tar"
                        dir("tiflash") {
                            // TODO: need to refactor this part, use shell script to do the job
                            // pass the ccache_source & cache_source to shell script by env
                            if (fileExists(ccache_source)) {
                                echo "ccache found"
                                sh """
                                cd /tmp
                                cp ${ccache_source} ccache.tar
                                tar -xf ccache.tar
                                cd -
                                """
                            } else {
                                echo "ccache not found"
                            }
                            sh """
                            ccache -o cache_dir="/tmp/.ccache"
                            ccache -o max_size=2G
                            ccache -o limit_multiple=0.99
                            ccache -o hash_dir=false
                            ccache -o compression=true
                            ccache -o compression_level=6
                            ccache -o read_only=false
                            ccache -z
                            """
                        }
                    }
                    }
                }
                stage("Proxy-Cache") {
                    steps {
                    script {
                        def proxy_suffix = "amd64-linux-llvm"
                        def cache_source = "/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-${proxy_suffix}"
                        // TODO: need to refactor this part, use shell script to do the job
                        // cache proxy lib by pvc or nfs or fileserver ?
                        if (fileExists(cache_source)) {
                            echo "proxy cache found"
                            dir("tiflash") {
                                sh """
                                cp ${cache_source} libtiflash_proxy.so
                                chmod +x libtiflash_proxy.so
                                """
                            }
                        } else {
                            echo "proxy cache not found"
                        }
                        sh label: "link cargo cache", script: """
                            mkdir -p ~/.cargo/registry
                            mkdir -p ~/.cargo/git
                            mkdir -p /home/jenkins/agent/rust/registry/cache
                            mkdir -p /home/jenkins/agent/rust/registry/index
                            mkdir -p /home/jenkins/agent/rust/git/db
                            mkdir -p /home/jenkins/agent/rust/git/checkouts

                            rm -rf ~/.cargo/registry/cache && ln -s /home/jenkins/agent/rust/registry/cache ~/.cargo/registry/cache
                            rm -rf ~/.cargo/registry/index && ln -s /home/jenkins/agent/rust/registry/index ~/.cargo/registry/index
                            rm -rf ~/.cargo/git/db && ln -s /home/jenkins/agent/rust/git/db ~/.cargo/git/db
                            rm -rf ~/.cargo/git/checkouts && ln -s /home/jenkins/agent/rust/git/checkouts ~/.cargo/git/checkouts

                            rm -rf ~/.rustup/tmp
                            rm -rf ~/.rustup/toolchains
                            mkdir -p /home/jenkins/agent/rust/rustup-env/tmp
                            mkdir -p /home/jenkins/agent/rust/rustup-env/toolchains
                            ln -s /home/jenkins/agent/rust/rustup-env/tmp ~/.rustup/tmp
                            ln -s /home/jenkins/agent/rust/rustup-env/toolchains ~/.rustup/toolchains
                        """
                    }   
                    }
                }
            }
        }
        stage("Build Dependency and Utils") {
            parallel {
                stage("Cluster Manage") { 
                    steps {
                    // NOTE: cluster_manager is deprecated since release-6.0 (include)
                    echo "cluster_manager is deprecated"
                    }
                }
                stage("TiFlash Proxy") {
                    steps {
                        script {
                        if (proxy_cache_ready) {
                            echo "skip becuase of cache"
                        } else {
                            echo "proxy cache not ready"
                        }
                        }
                    }
                }
            }
        }
        stage("Configure Project") {
            steps {
                script {
                    def toolchain = "llvm"
                    def generator = 'Ninja'
                    def coverage_flag = ""
                    def diagnostic_flag = ""
                    def compatible_flag = ""
                    def openssl_root_dir = ""
                    def prebuilt_dir_flag = ""
                    if (proxy_cache_ready) {
                        // only for toolchain is llvm
                        prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                        sh """
                        mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release
                        cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/releasev
                        """
                    }
                    // create build dir and install dir
                    sh label: "create build & install dir", script: """
                    mkdir -p ${WORKSPACE}/build
                    mkdir -p ${WORKSPACE}/install/tiflash
                    """
                    dir("${WORKSPACE}/build") {
                        sh label: "configure project", script: """
                        cmake '${WORKSPACE}/tiflash' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} \\
                            -G '${generator}' \\
                            -DENABLE_FAILPOINTS=true \\
                            -DCMAKE_BUILD_TYPE=debug \\
                            -DCMAKE_PREFIX_PATH='/usr/local' \\
                            -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                            -DENABLE_TESTS=true \\
                            -DUSE_CCACHE=true \\
                            -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                            -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                            -DRUN_HAVE_STD_REGEX=0 \\
                        """
                    }
                }
            }
        }
        stage("Build TiFlash") {
            steps {
                sh """
                cmake --build '${WORKSPACE}/build' --target gtests_dbms gtests_libcommon gtests_libdaemon --parallel 12
                """
                sh """
                cp '${WORKSPACE}/build/dbms/gtests_dbms' '${WORKSPACE}/install/tiflash/'
                cp '${WORKSPACE}/build/libs/libcommon/src/tests/gtests_libcommon' '${WORKSPACE}/install/tiflash/'
                cmake --install ${WORKSPACE}/build --component=tiflash-gtest --prefix='${WORKSPACE}/install/tiflash'
                """
                sh """
                target=`realpath \$(find . -executable | grep -v gtests_libdaemon.dir | grep gtests_libdaemon)`
                cp \$target '${WORKSPACE}/install/tiflash/'
                """
                sh """
                ccache -s
                ls -lha ${WORKSPACE}/install/tiflash/
                """
            }
        }

        stage("Post Build") {
            parallel {
                stage("Upload Build Artifacts") {
                    steps {
                        dir("${WORKSPACE}/install") {
                            sh """
                            tar -czf 'tiflash.tar.gz' 'tiflash'
                            """
                            archiveArtifacts artifacts: "tiflash.tar.gz"
                        }
                    }
                }
                stage("Upload Build Data") {
                    steps {
                        dir("${WORKSPACE}/build") {
                            sh """
                            tar -cavf build-data.tar.xz \$(find . -name "*.h" -o -name "*.cpp" -o -name "*.cc" -o -name "*.hpp" -o -name "*.gcno" -o -name "*.gcna")
                            """
                            archiveArtifacts artifacts: "build-data.tar.xz", allowEmptyArchive: true
                        }
                        dir("${WORKSPACE}/tiflash") {
                            sh """
                            tar -cavf source-patch.tar.xz \$(find . -name "*.pb.h" -o -name "*.pb.cc")
                            """
                            archiveArtifacts artifacts: "source-patch.tar.xz", allowEmptyArchive: true
                        }
                    }
                }
            }
        }

        stage("Unit Test Prepare") {
            steps {
                sh """
                ln -sf ${WORKSPACE}/install/tiflash /tiflash
                """
                sh """
                ls -lha ${WORKSPACE}/tiflash
                ln -sf ${WORKSPACE}/tiflash/tests /tests
                """
                dir("${WORKSPACE}/tiflash") {
                    echo "temp skip here"
                }
                dir("${WORKSPACE}/build") {
                    echo "temp skip here"
                }
            }
        }
        stage("Run Tests") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    parallelism=12
                    rm -rf /tmp-memfs/tiflash-tests
                    mkdir -p /tmp-memfs/tiflash-tests
                    export TIFLASH_TEMP_DIR=/tmp-memfs/tiflash-tests

                    mkdir -p /root/.cache
                    source /tests/docker/util.sh
                    export LLVM_PROFILE_FILE="/tiflash/profile/unit-test-%\${parallelism}m.profraw"
                    show_env
                    ENV_VARS_PATH=/tests/docker/_env.sh OUTPUT_XML=true NPROC=\${parallelism} /tests/run-gtest.sh
                    """
                }
            }
        }
    }
}
