// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.2 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"  // TODO: need to adjust namespace after test
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/release-8.2/pod-pull_unit-test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final PARALLELISM = 12
final dependency_dir = "/home/jenkins/agent/dependency"
Boolean proxy_cache_ready = false
String proxy_commit_hash = null


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
        timeout(time: 90, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    env
                    hostname
                    df -h
                    free -hm
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
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
                        retry(2) {
                            prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            dir("contrib/tiflash-proxy") {
                                proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                println "proxy_commit_hash: ${proxy_commit_hash}"
                            }
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
                            pwd
                            ccache_tar_file="/home/jenkins/agent/ccache/pagetools-tests-amd64-linux-llvm-debug-master-failpoints.tar"
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
                            ccache -o limit_multiple=0.99
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
                            proxy_cache_ready = fileExists("/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-amd64-linux-llvm")
                            println "proxy_cache_ready: ${proxy_cache_ready}"
                            sh label: "copy proxy if exist", script: """
                            proxy_suffix="amd64-linux-llvm"
                            proxy_cache_file="/home/jenkins/agent/proxy-cache/${proxy_commit_hash}-\${proxy_suffix}"
                            if [ -f \$proxy_cache_file ]; then
                                echo "proxy cache found"
                                mkdir -p ${WORKSPACE}/tiflash/libs/libtiflash-proxy
                                cp \$proxy_cache_file ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
                                chmod +x ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so
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
                    if (proxy_cache_ready) {
                        // only for toolchain is llvm
                        prebuilt_dir_flag = "-DPREBUILT_LIBS_ROOT='${WORKSPACE}/tiflash/contrib/tiflash-proxy/'"
                        sh """
                        mkdir -p ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release
                        cp ${WORKSPACE}/tiflash/libs/libtiflash-proxy/libtiflash_proxy.so ${WORKSPACE}/tiflash/contrib/tiflash-proxy/target/release/
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
                            -DCMAKE_BUILD_TYPE=Debug \\
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
                dir("${WORKSPACE}/tiflash") {
                sh """
                    cmake --build '${WORKSPACE}/build' --target gtests_dbms gtests_libcommon gtests_libdaemon --parallel ${PARALLELISM}
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
