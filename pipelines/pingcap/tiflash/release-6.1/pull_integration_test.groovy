// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-6.1 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/release-6.1/pod-pull_build.yaml'
final POD_INTEGRATIONTEST_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/release-6.1/pod-pull_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final PARALLELISM = 16
final dependency_dir = "/home/jenkins/agent/dependency"
Boolean proxy_cache_ready = false
String proxy_commit_hash = null
String tiflash_commit_hash = null

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
                    go env
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
                            prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            tiflash_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                            println "tiflash_commit_hash: ${tiflash_commit_hash}"
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
                            // TODO: need to find default backup cache for branch which just created
                            sh label: "copy ccache if exist", script: """
                            ccache_tar_file="/home/jenkins/agent/ccache/tiflash-amd64-linux-llvm-debug-${REFS.base_ref}-failpoints.tar"
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
                            else
                                echo "proxy cache not found"
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
                            -DENABLE_TESTS=false \\
                            -DUSE_CCACHE=true \\
                            -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                            -DUSE_INTERNAL_TIFLASH_PROXY=${!proxy_cache_ready} \\
                            -DRUN_HAVE_STD_REGEX=0 \\
                        """
                    }
                }
            }
        }
        stage("Format Check") {
            steps {
                script {
                    def target_branch = REFS.base_ref
                    def diff_flag = "--dump_diff_files_to '/tmp/tiflash-diff-files.json'"
                    def fileExists = sh(script: "test -f ${WORKSPACE}/tiflash/format-diff.py && echo 'true' || echo 'false'", returnStdout: true).trim() == 'true'
                    if (!fileExists) {
                        echo "skipped format check because this branch does not support format"
                        return
                    }
                    // TODO: need to check format-diff.py for more details
                    // currently, we checkout tiflash pr code in pre-merge method.
                    // whether to get the diff file in pull-reqeust.
                    dir("${WORKSPACE}/tiflash") {
                        sh """
                        python3 \\
                            ${WORKSPACE}/tiflash/format-diff.py ${diff_flag} \\
                            --repo_path '${WORKSPACE}/tiflash' \\
                            --check_formatted \\
                            --diff_from \$(git merge-base origin/${target_branch} HEAD)
                        """
                    }
                }
            }
        }
        stage("Build TiFlash") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh """
                    cmake --build '${WORKSPACE}/build' --target tiflash --parallel ${PARALLELISM}
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
                stage("Static Analysis"){
                    steps {
                        script {
                            def generator = "Ninja"
                            def include_flag = ""
                            def fix_compile_commands = "${WORKSPACE}/tiflash/release-centos7-llvm/scripts/fix_compile_commands.py"
                            def run_clang_tidy = "${WORKSPACE}/tiflash/release-centos7-llvm/scripts/run-clang-tidy.py"
                            dir("${WORKSPACE}/build") {
                                sh """
                                NPROC=\$(nproc || grep -c ^processor /proc/cpuinfo || echo '1')
                                cmake "${WORKSPACE}/tiflash" \\
                                    -DENABLE_TESTS=false \\
                                    -DCMAKE_BUILD_TYPE=Debug \\
                                    -DUSE_CCACHE=OFF \\
                                    -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \\
                                    -DRUN_HAVE_STD_REGEX=0 \\
                                    -G '${generator}'
                                python3 ${fix_compile_commands} ${include_flag} \\
                                    --file_path=compile_commands.json \\
                                    --load_diff_files_from "/tmp/tiflash-diff-files.json"
                                python3 ${run_clang_tidy} -p \$(realpath .) -j \$NPROC --files ".*/tiflash/dbms/*"
                                """
                            }
                        }
                    }
                }
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
            }
        }

        stage("Cache code and artifact") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    cache(path: "./", includes: '**/*', key: "ws/pull-tiflash-integration-tests/${BUILD_TAG}") {
                        dir('tests/.build') {
                            sh """
                            cp -r ${WORKSPACE}/install/* ./
                            pwd && ls -alh
                            """
                        }
                        // remove .git and contrib to save cache space
                        sh """
                        git status
                        git show --oneline -s
                        rm -rf .git
                        rm -rf contrib
                        du -sh ./
                        ls -alh
                        """
                    }
                }
            }
        }

        stage('Integration Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PATH'
                        values 'tidb-ci', 'delta-merge-test', 'fullstack-test', 'fullstack-test2'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_INTEGRATIONTEST_TEMPLATE_FILE
                        defaultContainer 'docker'
                        retries 5
                        customWorkspace "/home/jenkins/agent/workspace/tiflash-integration-test"
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir("${WORKSPACE}/tiflash") {
                                cache(path: "./", includes: '**/*', key: "ws/pull-tiflash-integration-tests/${BUILD_TAG}") {
                                    sh """
                                    printenv
                                    pwd && ls -alh
                                    """
                                    dir("tests/${TEST_PATH}") {
                                        echo "path: ${pwd()}"
                                        sh "docker ps -a && docker version"
                                        // TODO: check the env TAG, currently the tiflash_commmit_hash is not the pr latest commit hash
                                        // because we checkout tiflash pr code in pre-merge method.
                                        script {
                                            def pdBranch = component.computeBranchFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'release-6.1')
                                            def tikvBranch = component.computeBranchFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'release-6.1')
                                            def tidbBranch = component.computeBranchFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'release-6.1')
                                            sh label: "run integration tests", script: """
                                            PD_BRANCH=${pdBranch} TIKV_BRANCH=${tikvBranch} TIDB_BRANCH=${tidbBranch} TAG=${tiflash_commit_hash} BRANCH=${REFS.base_ref} ./run.sh
                                            """
                                        }
                                    }
                                }
                            }
                        }
                        post {
                            unsuccessful {
                                script {
                                    dir("${WORKSPACE}/tiflash/tests/${TEST_PATH}") {
                                        println "Test failed, archive the log"
                                        sh label: "debug fail", script: """
                                            docker ps -a
                                            mv log ${TEST_PATH}-log
                                            find ${TEST_PATH}-log -name '*.log' | xargs tail -n 500
                                        """
                                        sh label: "archive logs", script: """
                                            chown -R 1000:1000 ./
                                            find ${TEST_PATH}-log -type f -name "*.log" -exec tar -czvf ${TEST_PATH}-logs.tar.gz {} +
                                            chown -R 1000:1000 ./
                                            ls -alh ${TEST_PATH}-logs.tar.gz
                                        """
                                        archiveArtifacts(artifacts: "${TEST_PATH}-logs.tar.gz", allowEmptyArchive: true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
