// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_build.yaml'
final POD_INTEGRATIONTEST_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final dependency_dir = "/home/jenkins/agent/dependency"
final TIFLASH_TEST_IMAGE = 'ghcr.io/pingcap-qe/cd/builders/tiflash:v2025.4.15-rocky8-llvm-17.0.6-v2'
final TEST_WORKSPACE_CACHE_FOLDER = 'tiflash-it-build'
Boolean proxy_cache_ready = false
Boolean build_cache_ready = false
String proxy_commit_hash = null
String tiflash_commit_hash = null
Boolean libclara_cache_ready = false
String libclara_commit_hash = null

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'runner'
            retries 5
            customWorkspace "/home/jenkins/agent/workspace/tiflash-build-common"
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            when {
                expression { !build_cache_ready }
            }
            options { timeout(time: 15, unit: 'MINUTES') }
            steps {
                dir("tiflash") {
                    script {
                        sh """
                        git config --global --add safe.directory "*"
                        git version
                        """
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID, true, 'https://github.com')
                        retry(2) {
                            sh "git status"
                            tiflash_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                            println "tiflash_commit_hash: ${tiflash_commit_hash}"

                            // Get tiflash-proxy commit hash.
                            // For submodule, we need to enter the submodule directory and get the commit hash from there.
                            dir("contrib/tiflash-proxy") {
                                proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                println "proxy_commit_hash: ${proxy_commit_hash}"
                            }

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
        stage("License check") {
            when {
                expression { !build_cache_ready }
            }
            steps {
                dir("${WORKSPACE}/tiflash") {
                    container('utils') {
                        sh label: "get license-eye tool", script: '${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --license-eye=v0.4.0'
                    }
                    sh label: "license header check", script: """
                        echo "license check"
                        if [[ -f .github/licenserc.yml ]]; then
                            chmod +x ./license-eye
                            ./license-eye -c .github/licenserc.yml header check
                        else
                            echo "skip license check"
                            exit 0
                        fi
                    """
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
            when {
                expression { !build_cache_ready }
            }
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
        stage("Format Check") {
            when {
                expression { !build_cache_ready }
            }
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
                    dir("${WORKSPACE}/tiflash") {
                        sh """
                        python3 \\
                            ${WORKSPACE}/tiflash/format-diff.py ${diff_flag} \\
                            --repo_path '${WORKSPACE}/tiflash' \\
                            --check_formatted \\
                            --diff_from \$(git merge-base origin/${target_branch} HEAD)

                        cat /tmp/tiflash-diff-files.json
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
        stage("Post Build") {
            when {
                expression { !build_cache_ready }
            }
            parallel {
                stage("Static Analysis"){
                    steps {
                        script {
                            def generator = "Ninja"
                            def include_flag = ""
                            def fix_compile_commands = "${WORKSPACE}/tiflash/release-linux-llvm/scripts/fix_compile_commands.py"
                            def run_clang_tidy = "${WORKSPACE}/tiflash/release-linux-llvm/scripts/run-clang-tidy.py"
                            dir("${WORKSPACE}/build") {
                                sh label: "debug diff files", script: """
                                cat /tmp/tiflash-diff-files.json
                                """
                                sh label: "run clang tidy", script: """
                                cat /tmp/tiflash-diff-files.json
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
                                python3 ${run_clang_tidy} -p \$(realpath .) -j 12 --files ".*/tiflash/dbms/*"
                                """
                            }
                        }
                    }
                }
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
            }
        }
        stage("Cache code and artifact") {
            when {
                expression { !build_cache_ready }
            }
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh label: "change permission", script: """
                        chown -R 1000:1000 ./
                    """
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/${TEST_WORKSPACE_CACHE_FOLDER}") {
                       dir('tests/.build') {
                            sh label: "archive tiflash binary", script: """
                            cp -r ${WORKSPACE}/install/* ./
                            pwd && ls -alh
                            """
                        }
                        sh label: "clean unnecessary dirs", script: """
                        git status
                        git show --oneline -s
                        rm -rf .git
                        rm -rf contrib
                        rm -rf tests/fullstack-test-next-gen-columnar
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
                        yaml pod_label.withCiLabels(POD_INTEGRATIONTEST_TEMPLATE_FILE, REFS)
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'docker'
                        retries 5
                        customWorkspace "/home/jenkins/agent/workspace/tiflash-integration-test"
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_path: env.TEST_PATH]) }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir("${WORKSPACE}/tiflash") {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/${TEST_WORKSPACE_CACHE_FOLDER}") {
                                    println "restore from cache key: ws/${BUILD_TAG}/${TEST_WORKSPACE_CACHE_FOLDER}"
                                    sh label: "debug info", script: """
                                    printenv
                                    pwd && ls -alh
                                    """
                                    dir("tests/${TEST_PATH}") {
                                        echo "path: ${pwd()}"
                                        sh label: "debug docker info", script: """
                                        docker ps -a && docker version
                                        """
                                        script {
                                            def pdBranch = component.computeBranchFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
                                            def tikvBranch = component.computeBranchFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
                                            def tidbBranch = component.computeBranchFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
                                            def tidbImage = "${OCI_ARTIFACT_HOST}/pingcap/tidb/images/tidb-server:${tidbBranch}"
                                            def tidbFailpointImage = "${OCI_ARTIFACT_HOST}/pingcap/tidb/images/tidb-server:${tidbBranch}-failpoint"
                                            def tidbRuntimeImage = env.TEST_PATH == 'tidb-ci' ? tidbFailpointImage : tidbImage
                                            withEnv([
                                                "PD_IMAGE=${OCI_ARTIFACT_HOST}/tikv/pd/image:${pdBranch}",
                                                "TIKV_IMAGE=${OCI_ARTIFACT_HOST}/tikv/tikv/image:${tikvBranch}",
                                                "TIDB_IMAGE=${tidbRuntimeImage}",
                                                "TIDB_FAILPOINT_IMAGE=${tidbFailpointImage}",
                                                "TIFLASH_IMAGE=${TIFLASH_TEST_IMAGE}",
                                            ]) {
                                                withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                                                    sh label: "prepare docker images", script: '''
                                                        set -eux
                                                        mkdir -p ~/.docker
                                                        cp "${DOCKER_CONFIG_JSON}" ~/.docker/config.json
                                                        for i in $(seq 1 30); do
                                                            if docker version; then
                                                                break
                                                            fi
                                                            sleep 2
                                                        done
                                                        docker ps -a

                                                        timeout 300 docker pull "${PD_IMAGE}"
                                                        timeout 300 docker pull "${TIKV_IMAGE}"
                                                        timeout 300 docker pull "${TIDB_IMAGE}"
                                                        timeout 300 docker pull "${TIDB_FAILPOINT_IMAGE}" || true
                                                        timeout 600 docker pull "${TIFLASH_IMAGE}"

                                                        find ../docker . -name '*.yaml' -type f -exec sed -i \
                                                            -e "s#hub.pingcap.net/tiflash/tiflash-ci-base:rocky8-20241028#${TIFLASH_IMAGE}#g" \
                                                            -e "s#hub.pingcap.net/tiflash/tiflash-ci-base:rocky9-20250529#${TIFLASH_IMAGE}#g" \
                                                            -e 's#hub.pingcap.net/tiflash/tics:${TAG:-master}#'"${TIFLASH_IMAGE}"'#g' \
                                                            -e 's#hub.pingcap.net/tikv/pd/image:${PD_BRANCH:-master}#'"${PD_IMAGE}"'#g' \
                                                            -e "s#hub.pingcap.net/tikv/pd/image:master#${PD_IMAGE}#g" \
                                                            -e 's#hub.pingcap.net/tikv/tikv/image:${TIKV_BRANCH:-master}#'"${TIKV_IMAGE}"'#g' \
                                                            -e "s#hub.pingcap.net/tikv/tikv/image:master#${TIKV_IMAGE}#g" \
                                                            -e 's#hub.pingcap.net/pingcap/tidb/images/tidb-server:${TIDB_BRANCH:-master}-failpoint#'"${TIDB_FAILPOINT_IMAGE}"'#g' \
                                                            -e 's#hub.pingcap.net/pingcap/tidb/images/tidb-server:${TIDB_BRANCH:-master}#'"${TIDB_IMAGE}"'#g' \
                                                            -e "s#hub.pingcap.net/pingcap/tidb/images/tidb-server:master-failpoint#${TIDB_FAILPOINT_IMAGE}#g" \
                                                            -e "s#hub.pingcap.net/pingcap/tidb/images/tidb-server:master#${TIDB_IMAGE}#g" \
                                                            -e 's#hub.pingcap.net/qa/pd:${PD_BRANCH:-master}#'"${PD_IMAGE}"'#g' \
                                                            -e 's#hub.pingcap.net/qa/tikv:${TIKV_BRANCH:-master}#'"${TIKV_IMAGE}"'#g' \
                                                            -e 's#hub.pingcap.net/qa/tidb:${TIDB_BRANCH:-master}-failpoint#'"${TIDB_FAILPOINT_IMAGE}"'#g' \
                                                            -e 's#hub.pingcap.net/qa/tidb:${TIDB_BRANCH:-master}#'"${TIDB_IMAGE}"'#g' \
                                                            -e "s#hub.pingcap.net/qa/pd:master#${PD_IMAGE}#g" \
                                                            -e "s#hub.pingcap.net/qa/tikv:master#${TIKV_IMAGE}#g" \
                                                            -e "s#hub.pingcap.net/qa/tidb:master-failpoint#${TIDB_FAILPOINT_IMAGE}#g" \
                                                            -e "s#hub.pingcap.net/qa/tidb:master#${TIDB_IMAGE}#g" \
                                                            {} +
                                                        rm -rf ~/.docker
                                                    '''
                                                }

                                                sh label: "run integration tests", script: """
                                                PD_BRANCH=${pdBranch} TIKV_BRANCH=${tikvBranch} TIDB_BRANCH=${tidbBranch} TAG=${tiflash_commit_hash} BRANCH=${REFS.base_ref} ./run.sh
                                                """
                                            }
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
                            success { script { matrixCache.markDone(REFS, 'Test', [test_path: env.TEST_PATH]) } }
                        }
                    }
                }
            }
        }
    }
}
