// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"  // TODO: need to adjust namespace after test
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-merged_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final PARALLELISM = 12
final dependency_dir = "/home/jenkins/agent/dependency"
final proxy_cache_dir = "/home/jenkins/agent/proxy-cache/refactor-pipelines"

Boolean proxy_cache_ready = false
String proxy_commit_hash = null
Boolean update_proxy_cache = true

Boolean libclara_cache_ready = false
String libclara_commit_hash = null
Boolean update_libclara_cache = true

Boolean update_ccache = true

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
    options {
        timeout(time: 120, unit: 'MINUTES')
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                dir("tiflash") {
                    retry(2) {
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
                            prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            // Get next-gen tiflash-proxy commit hash.
                            // For submodule, we need to enter the submodule directory and get the commit hash from there.
                            dir("contrib/tiflash-proxy-next-gen") {
                                proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                println "proxy_commit_hash: ${proxy_commit_hash}"
                            }

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
            parallel {
                stage("Ccache") {
                    steps {
                        script {
                            dir("tiflash") {
                                sh label: "copy ccache if exist", script: """
                                ccache_tar_file="/home/jenkins/agent/ccache/ccache-4.10.2/tiflash-amd64-linux-llvm-debug-${REFS.base_ref}-failpoints.tar"
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
                            proxy_cache_ready = sh(script: "test -f /home/jenkins/agent/proxy-cache/${proxy_commit_hash}-amd64-linux-llvm-next-gen && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'
                            println "proxy_cache_ready: ${proxy_cache_ready}"

                            sh label: "copy proxy if exist", script: """
                            proxy_suffix="amd64-linux-llvm-next-gen"
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
                stage("Cargo-Cache") {
                    steps {
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
        stage("Configure Project") {
            steps {
                script {
                    def toolchain = "llvm"
                    def generator = 'Ninja'
                    def next_gen_flag = "-DENABLE_NEXT_GEN=ON"
                    def coverage_flag = ""
                    def diagnostic_flag = ""
                    def compatible_flag = ""
                    def openssl_root_dir = ""
                    def prebuilt_dir_flag = ""
                    def libclara_flag = ""
                    if (proxy_cache_ready) {
                        // only for toolchain is llvm
                        prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy-next-gen/'"
                        sh """
                        mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy-next-gen/target/release
                        cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy-next-gen/target/release/
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
                        cmake '${WORKSPACE}/tiflash' ${prebuilt_dir_flag} ${coverage_flag} ${diagnostic_flag} ${compatible_flag} ${openssl_root_dir} ${libclara_flag} ${next_gen_flag} \\
                            -G '${generator}' \\
                            -DENABLE_FAILPOINTS=true \\
                            -DCMAKE_BUILD_TYPE=Debug \\
                            -DCMAKE_PREFIX_PATH='/usr/local' \\
                            -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                            -DENABLE_TESTS=false \\
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
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    cmake --build '${WORKSPACE}/build' --target tiflash --parallel 12
                    """
                    sh """
                    cmake --install '${WORKSPACE}/build' --component=tiflash-release --prefix='${WORKSPACE}/install/tiflash'
                    """
                    sh """
                    ccache -s
                    ls -lha ${WORKSPACE}/install/tiflash
                    """
                }
            }
        }
        stage("License check") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    // TODO: add license-eye to docker image
                    sh label: "license header check", script: """
                        echo "license check"
                        if [[ -f .github/licenserc.yml ]]; then
                            oras pull hub.pingcap.net/pingcap/ci-tools/license-eye:v0.4.0 --output .
                            chmod +x license-eye
                            ./license-eye -c .github/licenserc.yml header check
                        else
                            echo "skip license check"
                            exit 0
                        fi
                    """
                }
            }
        }
        stage("Post Build") {
            parallel {
                // stage("Upload Build Artifacts") {
                //     steps {
                //         dir("${WORKSPACE}/install") {
                //             sh label: "archive tiflash binary", script: """
                //             tar -czf 'tiflash.tar.gz' 'tiflash'
                //             """
                //             archiveArtifacts artifacts: "tiflash.tar.gz"
                //             sh """
                //             du -sh tiflash.tar.gz
                //             rm -rf tiflash.tar.gz
                //             """
                //         }
                //     }
                // }
                stage("Upload Ccache") {
                    steps {
                        dir("${WORKSPACE}/tiflash") {
                            sh label: "upload ccache", script: """
                            cd /tmp
                            rm -rf ccache.tar
                            tar -cf ccache.tar .ccache
                            ls -alh ccache.tar
                            cp ccache.tar /home/jenkins/agent/ccache/ccache-4.10.2/tiflash-amd64-linux-llvm-debug-${REFS.base_ref}-failpoints.tar
                            cd -
                            """
                        }
                    }
                }
                stage("Upload Proxy Cache") {
                    steps {
                        script {
                            if (update_proxy_cache) {
                                if (proxy_commit_hash && proxy_commit_hash =~ /^[0-9a-f]{40}$/) {
                                    def proxy_suffix = "amd64-linux-llvm-next-gen"
                                    def cache_destination = "/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-${proxy_suffix}"

                                    sh label: "upload proxy cache", script: """
                                        if [ -f "${cache_destination}" ]; then
                                            echo "Proxy cache already exists at ${cache_destination}, skip uploading"
                                        elif [ -f "${WORKSPACE}/install/tiflash/libtiflash_proxy.so" ]; then
                                            cp "${WORKSPACE}/install/tiflash/libtiflash_proxy.so" "${cache_destination}"
                                            echo "Proxy cache uploaded to ${cache_destination}"
                                        else
                                            echo "Proxy library not found, cache not updated"
                                        fi
                                    """
                                } else {
                                    echo "Skip uploading proxy cache because commit hash '${proxy_commit_hash}' is not valid"
                                }
                            } else {
                                echo "Skip because proxy cache refresh is disabled"
                            }
                        }
                    }
                }
                stage("Upload Libclara Cache") {
                    steps {
                        script {
                            if (update_libclara_cache) {
                                if (libclara_commit_hash && libclara_commit_hash =~ /^[0-9a-f]{40}$/) {
                                    def libclara_suffix = "amd64-linux-debug"
                                    def cache_destination = "/home/jenkins/agent/libclara-cache/${libclara_commit_hash}-${libclara_suffix}"

                                    sh label: "upload libclara cache", script: """
                                        if [ -d "${cache_destination}" ]; then
                                            echo "Libclara cache already exists at ${cache_destination}, skip uploading"
                                        elif [ -f "${WORKSPACE}/build/libs/libclara-cmake/libclara_sharedd.so" ]; then
                                            mkdir -p "${cache_destination}_tmp"
                                            cp "${WORKSPACE}/build/libs/libclara-cmake/libclara_sharedd.so" "${cache_destination}_tmp/"
                                            cp -RL "${WORKSPACE}/build/libs/libclara-cmake/cxxbridge" "${cache_destination}_tmp/"
                                            mv "${cache_destination}_tmp" "${cache_destination}"
                                            echo "Libclara cache uploaded to ${cache_destination}"
                                        else
                                            echo "Libclara library not found in local build, cache not updated"
                                        fi
                                    """
                                } else {
                                    echo "Skip uploading libclara cache because commit hash '${libclara_commit_hash}' is not valid"
                                }
                            } else {
                                echo "Skip because libclara cache refresh is disabled"
                            }
                        }
                    }
                }
            }
        }
    }
}
