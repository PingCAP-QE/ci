// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_build.yaml'
final POD_INTEGRATIONTEST_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "cloud-engine-nextgen")
final MINIO_VERSION = 'RELEASE.2025-07-23T15-54-02Z'
final TIFLASH_TEST_IMAGE = 'ghcr.io/pingcap-qe/cd/builders/tiflash:v2025.4.15-rocky8-llvm-17.0.6-v2'
final WORKSPACE_STASH_NAME = 'tiflash-next-gen-it-workspace'
final PARALLELISM = 12

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'runner'
            retries 2
            customWorkspace "/home/jenkins/agent/workspace/tiflash-build-common"
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    sh 'git config --global --add safe.directory "*" && git version'
                    script {
                        prow.checkoutRefs(REFS, GIT_CREDENTIALS_ID, 30, true, 'https://github.com')
                    }
                    sh 'chown 1000:1000 -R ./'
                }
            }
        }
        stage("License check") {
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
        stage("Prepare Ccache") {
            steps {
                script {
                    dir("tiflash") {
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
        stage("Configure Project") {
            steps {
                script {
                    def generator = 'Ninja'
                    def next_gen_flag = "-DENABLE_NEXT_GEN=ON"
                    // create build dir and install dir
                    sh label: "create build & install dir", script: """
                    mkdir -p ${WORKSPACE}/build
                    mkdir -p ${WORKSPACE}/install/tiflash
                    """
                    dir("${WORKSPACE}/build") {
                        sh label: "configure project", script: """
                        cmake '${WORKSPACE}/tiflash' ${next_gen_flag} \\
                            -G '${generator}' \\
                            -DENABLE_FAILPOINTS=true \\
                            -DCMAKE_BUILD_TYPE=Debug \\
                            -DCMAKE_PREFIX_PATH='/usr/local' \\
                            -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                            -DENABLE_TESTS=false \\
                            -DUSE_CCACHE=true \\
                            -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                            -DUSE_INTERNAL_TIFLASH_PROXY=true \\
                            -DUSE_INTERNAL_LIBCLARA=true \\
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
        stage("Post Build") {
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
                        python3 ${run_clang_tidy} -p \$(realpath .) -j ${PARALLELISM} --files ".*/tiflash/dbms/*"
                        """
                    }
                }
            }
        }
        stage("Stash Test Workspace") {
            steps {
                dir("${WORKSPACE}/tiflash") {
                    sh label: "change permission", script: """
                        chown -R 1000:1000 ./
                    """
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
                    stash includes: '**/*', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
                }
            }
        }

        stage('Integration Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PATH'
                        values 'delta-merge-test', 'fullstack-test-next-gen'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_INTEGRATIONTEST_TEMPLATE_FILE, REFS)
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'docker'
                        retries 2
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
                                unstash name: WORKSPACE_STASH_NAME
                                dir("tests/${TEST_PATH}") {
                                    withEnv([
                                        "PD_IMAGE=${OCI_ARTIFACT_HOST}/tikv/pd/image:${OCI_TAG_PD}",
                                        "TIKV_IMAGE=${OCI_ARTIFACT_HOST}/tikv/tikv/image:${OCI_TAG_TIKV}",
                                        "TIDB_IMAGE=${OCI_ARTIFACT_HOST}/pingcap/tidb/images/tidb-server:${OCI_TAG_TIDB}",
                                        "MINIO_IMAGE=quay.io/minio/minio:${MINIO_VERSION}",
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
                                                    timeout 300 docker pull "${MINIO_IMAGE}"
                                                    timeout 600 docker pull "${TIFLASH_IMAGE}"

                                                    find ../docker . -name '*.yaml' -type f -exec sed -i \
                                                        -e 's#${PD_IMAGE:[^}]*}#'"${PD_IMAGE}"'#g' \
                                                        -e 's#${TIKV_IMAGE:[^}]*}#'"${TIKV_IMAGE}"'#g' \
                                                        -e 's#${TIDB_IMAGE:[^}]*}#'"${TIDB_IMAGE}"'#g' \
                                                        -e 's#${MINIO_IMAGE:[^}]*}#'"${MINIO_IMAGE}"'#g' \
                                                        -e 's#${TIFLASH_IMAGE:[^}]*}#'"${TIFLASH_IMAGE}"'#g' \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/tiflash/tiflash-ci-base:rocky8-20241028#${TIFLASH_IMAGE}#g" \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/tiflash/tiflash-ci-base:rocky9-20250529#${TIFLASH_IMAGE}#g" \
                                                        -e 's#[[:alnum:].-]*[.][[:alnum:].-]*/tiflash/tics:${TAG:-master}#'"${TIFLASH_IMAGE}"'#g' \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/test-infra/minio:latest#${MINIO_IMAGE}#g" \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/tikv/pd/image:master#${PD_IMAGE}#g" \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/tikv/tikv/image:master#${TIKV_IMAGE}#g" \
                                                        -e "s#[[:alnum:].-]*[.][[:alnum:].-]*/pingcap/tidb/images/tidb-server:master#${TIDB_IMAGE}#g" \
                                                        -e 's#[[:alnum:].-]*[.][[:alnum:].-]*/qa/pd:${PD_BRANCH:-master}#'"${PD_IMAGE}"'#g' \
                                                        -e 's#[[:alnum:].-]*[.][[:alnum:].-]*/qa/tikv:${TIKV_BRANCH:-master}#'"${TIKV_IMAGE}"'#g' \
                                                        -e 's#[[:alnum:].-]*[.][[:alnum:].-]*/qa/tidb:${TIDB_BRANCH:-master}#'"${TIDB_IMAGE}"'#g' \
                                                        {} +
                                                    rm -rf ~/.docker
                                                '''
                                        }

                                        sh label: "run the tests", script: "TAG=${REFS.pulls[0].sha} BRANCH=${REFS.base_ref} ENABLE_NEXT_GEN=true ./run.sh"
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
