// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"  // TODO: need to adjust namespace after test
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_unit-test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final PARALLELISM = 12
final dependency_dir = "/home/jenkins/agent/dependency"
Boolean build_cache_ready = false
Boolean proxy_cache_ready = false
String proxy_commit_hash = null
Boolean libclara_cache_ready = false
String libclara_commit_hash = null

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
            retries 5
            customWorkspace "/home/jenkins/agent/workspace/tiflash-build-common"
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    hostname
                    df -h
                    free -hm
                    gcc --version
                    cmake --version
                    clang --version
                    ccache --version
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
                script {
                    // test build cache, if cache is exist, then skip the following build steps
                    try {
                        dir("test-build-cache") {
                            cache(path: "./", includes: '**/*', key: prow.getCacheKey('tiflash', REFS, 'ut-build')){
                                // if file README.md not exist, then build-cache-ready is false
                                build_cache_ready = sh(script: "test -f README.md && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'
                                println "build_cache_ready: ${build_cache_ready}, build cache key: ${prow.getCacheKey('tiflash', REFS, 'ut-build')}"
                                println "skip build..."
                                // if build cache not ready, then throw error to avoid cache empty directory
                                // for the same cache key, if throw error, will skip the cache step
                                // the cache gets not stored if the key already exists or the inner-step has been failed
                                if (!build_cache_ready) {
                                    error "build cache not exist, start build..."
                                }
                            }
                        }
                    } catch (Exception e) {
                        println "build cache not ready: ${e}"
                    }
                }
            }
        }
        stage('Checkout') {
            when {
                expression { !build_cache_ready }
            }
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                dir("tiflash") {
                    script {
                        container("util") {
                            withCredentials(
                                [file(credentialsId: 'ks3util-config', variable: 'KS3UTIL_CONF')]
                            ) {
                                sh "rm -rf ./*"
                                sh "ks3util -c \$KS3UTIL_CONF cp -f ks3://ee-fileserver/download/cicd/daily-cache-code/src-tiflash.tar.gz src-tiflash.tar.gz"
                                sh """
                                ls -alh
                                chown 1000:1000 src-tiflash.tar.gz
                                tar -xf src-tiflash.tar.gz --strip-components=1 && rm -rf src-tiflash.tar.gz
                                ls -alh
                                """
                            }
                        }
                        sh """
                        git config --global --add safe.directory "*"
                        git version
                        git status
                        """
                        git.setSshKey(GIT_CREDENTIALS_ID)
                        retry(2) {
                            prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)

                            // get tiflash-proxy commit hash
                            proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H" -- contrib/tiflash-proxy').trim()
                            println "proxy_commit_hash: ${proxy_commit_hash}"

                            // get clara commit hash
                            libclara_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H" -- libs/libclara').trim()
                            println "libclara_commit_hash: ${libclara_commit_hash}"

                            sh """
                            chown 1000:1000 -R ./
                            """
                        }
                    }
                }
            }
        }
        stage("Prepare Cache") {
            when {
                expression { !build_cache_ready }
            }
            parallel {
                stage("Ccache") {
                    steps {
                    script {
                        dir("tiflash") {
                            sh label: "copy ccache if exist", script: """
                            pwd
                            ccache_tar_file="/home/jenkins/agent/ccache/ccache-4.10.2/pagetools-tests-amd64-linux-llvm-debug-${REFS.base_ref}-failpoints.tar"
                            if [ -f \$ccache_tar_file ]; then
                                echo "ccache found"
                                cd /tmp
                                cp -r \$ccache_tar_file ccache.tar
                                tar -xf ccache.tar
                                ls -lha /tmp
                            else
                                echo "ccache not found"
                            fi
                            """
                            sh label: "config ccache", script: """
                            ccache -o cache_dir="/tmp/.ccache"
                            ccache -o max_size=2G
                            ccache -o hash_dir=false
                            ccache -o compression=true
                            ccache -o compression_level=6
                            ccache -o read_only=true
                            ccache -z
                            """
                        }
                    }
                    }
                }
                stage("Proxy-Cache") {
                    steps {
                        script {
                            proxy_cache_ready = sh(script: "test -f /home/jenkins/agent/proxy-cache/${proxy_commit_hash}-amd64-linux-llvm && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'
                            println "proxy_cache_ready: ${proxy_cache_ready}"

                            sh label: "copy proxy if exist", script: """
                            proxy_suffix="amd64-linux-llvm"
                            proxy_cache_file="/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-\${proxy_suffix}"
                            if [ -f \$proxy_cache_file ]; then
                                echo "proxy cache found"
                                mkdir -p ${WORKSPACE}/tiflash/libs/libtiflash-proxy
                                cp \$proxy_cache_file ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                                chmod +x ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                                chown 1000:1000 ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                            else
                                echo "proxy cache not found"
                            fi
                            """
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
                stage("Libclara Cache") {
                    steps {
                        script {
                            libclara_cache_ready = sh(script: "test -d /home/jenkins/agent/libclara-cache/${libclara_commit_hash}-amd64-linux-debug && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'
                            println "libclara_cache_ready: ${libclara_cache_ready}"

                            sh label: "copy libclara if exist", script: """
                            libclara_suffix="amd64-linux-debug"
                            libclara_cache_dir="/home/jenkins/agent/libclara-cache/${libclara_commit_hash}-\${libclara_suffix}"
                            if [ -d \$libclara_cache_dir ]; then
                                echo "libclara cache found"
                                mkdir -p ${WORKSPACE}/tiflash/libs/libclara-prebuilt
                                cp -r \$libclara_cache_dir/* ${WORKSPACE}/tiflash/libs/libclara-prebuilt/
                                chmod +x ${WORKSPACE}/tiflash/libs/libclara-prebuilt/libclara_sharedd.so
                                chown -R 1000:1000 ${WORKSPACE}/tiflash/libs/libclara-prebuilt
                                ls -R ${WORKSPACE}/tiflash/libs/libclara-prebuilt
                            else
                                echo "libclara cache not found"
                            fi
                            """
                        }
                    }
                }
            }
        }
        stage("Build Dependency and Utils") {
            when {
                expression { !build_cache_ready }
            }
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
            when {
                expression { !build_cache_ready }
            }
            // TODO: need to simplify this part, all config and build logic should be in script in tiflash repo
            steps {
                script {
                    def toolchain = "llvm"
                    def generator = 'Ninja'
                    def coverage_flag = ""
                    def diagnostic_flag = ""
                    def compatible_flag = ""
                    def openssl_root_dir = ""
                    def prebuilt_dir_flag = ""
                    def libclara_flag = ""
                    if (proxy_cache_ready) {
                        // only for toolchain is llvm
                        prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                        sh """
                        mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release
                        cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release/
                        """
                    }
                    if (libclara_cache_ready) {
                        libclara_flag = "-DLIBCLARA_CXXBRIDGE_DIR='${WORKSPACE}/tiflash/libs/libclara-prebuilt/cxxbridge' -DLIBCLARA_LIBRARY='${WORKSPACE}/tiflash/libs/libclara-prebuilt/libclara_sharedd.so'"
                    }
                    // create build dir and install dir
                    sh label: "create build & install dir", script: """
                    mkdir -p ${WORKSPACE}/build
                    mkdir -p ${WORKSPACE}/install/tiflash
                    """
                    dir("${WORKSPACE}/build") {
                        sh label: "configure project", script: """
                        cmake '${WORKSPACE}/tiflash' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} ${libclara_flag} \\
                            -G '${generator}' \\
                            -DENABLE_FAILPOINTS=true \\
                            -DCMAKE_BUILD_TYPE=Debug \\
                            -DCMAKE_PREFIX_PATH='/usr/local' \\
                            -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                            -DENABLE_TESTS=true \\
                            -DUSE_CCACHE=true \\
                            -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                            -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                            -DUSE_INTERNAL_LIBCLARA=${!libclara_cache_ready} \\
                            -DRUN_HAVE_STD_REGEX=0 \\
                        """
                    }
                }
            }
        }
        stage("Build TiFlash") {
            when {
                expression { !build_cache_ready }
            }
            steps {
                dir("${WORKSPACE}/tiflash") {
                sh """
                    cmake --build '${WORKSPACE}/build' --target gtests_dbms gtests_libcommon gtests_libdaemon bench_dbms --parallel ${PARALLELISM}
                    """
                    sh """
                    cp '${WORKSPACE}/build/dbms/gtests_dbms' '${WORKSPACE}/install/tiflash/'
                    cp '${WORKSPACE}/build/libs/libcommon/src/tests/gtests_libcommon' '${WORKSPACE}/install/tiflash/'
                    cmake --install ${WORKSPACE}/build --component=tiflash-gtest --prefix='${WORKSPACE}/install/tiflash'
                    """
                }
                dir("${WORKSPACE}/build") {
                    sh """
                    target=`realpath \$(find . -executable | grep -v gtests_libdaemon.dir | grep gtests_libdaemon)`
                    cp \$target '${WORKSPACE}/install/tiflash/'
                    """
                }
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    ccache -s
                    ls -lha ${WORKSPACE}/install/tiflash/
                    """
                }
            }
        }

        stage("Post Build") {
            when {
                expression { !build_cache_ready }
            }
            parallel {
                stage("Upload Build Artifacts") {
                    steps {
                        dir("${WORKSPACE}/install") {
                            sh label: "archive tiflash binary", script: """
                            tar -czf 'tiflash.tar.gz' 'tiflash'
                            """
                            archiveArtifacts artifacts: "tiflash.tar.gz"
                            sh """
                            du -sh tiflash.tar.gz
                            rm -rf tiflash.tar.gz
                            """
                        }
                    }
                }
                stage("Upload Build Data") {
                    steps {
                        dir("${WORKSPACE}/build") {
                            sh label: "archive build data", script: """
                            tar -cavf build-data.tar.xz \$(find . -name "*.h" -o -name "*.cpp" -o -name "*.cc" -o -name "*.hpp" -o -name "*.gcno" -o -name "*.gcna")
                            """
                            archiveArtifacts artifacts: "build-data.tar.xz", allowEmptyArchive: true
                            sh """
                            du -sh build-data.tar.xz
                            rm -rf build-data.tar.xz
                            """
                        }
                        dir("${WORKSPACE}/tiflash") {
                            sh label: "archive source patch", script: """
                            tar -cavf source-patch.tar.xz \$(find . -name "*.pb.h" -o -name "*.pb.cc")
                            """
                            archiveArtifacts artifacts: "source-patch.tar.xz", allowEmptyArchive: true
                            sh """
                            du -sh source-patch.tar.xz
                            rm -rf source-patch.tar.xz
                            """
                        }
                    }
                }
            }
        }

        stage("Unit Test Prepare") {
            steps {
                script {
                    dir("${WORKSPACE}/tiflash") {
                        sh label: "change permission", script: """
                            chown -R 1000:1000 ./
                        """
                        cache(path: "./", includes: '**/*', key: prow.getCacheKey('tiflash', REFS, 'ut-build')) {
                            if (build_cache_ready) {
                                println "build cache exist, restore from cache key: ${prow.getCacheKey('tiflash', REFS, 'ut-build')}"
                                sh """
                                du -sh ./
                                ls -alh ./
                                ls -alh tests/.build/
                                """
                            } else {
                                println "build cache not exist, clean git repo for cache"
                                sh label: "clean git repo", script: """
                                git status
                                git show --oneline -s
                                mkdir tests/.build
                                cp -r ${WORKSPACE}/install/* tests/.build/
                                rm -rf .git
                                rm -rf contrib
                                du -sh ./
                                ls -alh
                                """
                            }
                        }
                    }
                }
                sh label: "link tiflash and tests", script: """
                ls -lha ${WORKSPACE}/tiflash
                ln -sf ${WORKSPACE}/tiflash/tests/.build/tiflash /tiflash
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
                    sh label: "run tests", script: """
                    parallelism=${PARALLELISM}
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
