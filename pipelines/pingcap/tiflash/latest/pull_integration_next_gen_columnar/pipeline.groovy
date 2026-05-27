// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final BRANCH_ALIAS = 'latest'
final COLUMNAR_JOB_NAME = 'pull_integration_next_gen_columnar'
final POD_TEMPLATE_FILE_BUILD = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${COLUMNAR_JOB_NAME}/pod-build.yaml"
final POD_TEMPLATE_FILE_TEST = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${COLUMNAR_JOB_NAME}/pod-test.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "cloud-engine-nextgen")
final MINIO_VERSION = 'RELEASE.2025-07-23T15-54-02Z'
final TIFLASH_TEST_IMAGE = 'ghcr.io/pingcap-qe/cd/builders/tiflash:v2025.4.15-rocky8-llvm-17.0.6-v2'
final TEST_WORKSPACE_CACHE_FOLDER = 'tiflash-next-gen-columnar'

String tiflash_commit_hash = null

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout & Build') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE_BUILD, REFS)
                    retries 2
                    workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
                    defaultContainer 'runner'
                }
            }
            stages {
                stage('Checkout') {
                    options { timeout(time: 20, unit: 'MINUTES') }
                    steps {
                        dir(REFS.repo) {
                            script {
                                sh label: "trust Jenkins workspace for git", script: 'git config --global --add safe.directory "*"'
                                prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID, true)
                                tiflash_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                                println "tiflash_commit_hash: ${tiflash_commit_hash}"
                            }
                        }
                    }
                }
                stage('Prepare Cache') {
                    steps {
                        dir(REFS.repo) {
                            sh label: "prepare local build cache", script: '''
                                set -eux
                                git config --global --add safe.directory "*"

                                mkdir -p /home/jenkins/agent/ccache
                                ccache -o cache_dir="/home/jenkins/agent/ccache"
                                ccache -o max_size=20G
                                ccache -o hash_dir=false
                                ccache -o compression=true
                                ccache -o compression_level=6
                                ccache -z

                                mkdir -p ~/.cargo/registry ~/.cargo/git ~/.rustup
                                mkdir -p /home/jenkins/agent/rust/registry/cache
                                mkdir -p /home/jenkins/agent/rust/registry/index
                                mkdir -p /home/jenkins/agent/rust/git/db
                                mkdir -p /home/jenkins/agent/rust/git/checkouts
                                mkdir -p /home/jenkins/agent/rust/rustup-env/tmp
                                mkdir -p /home/jenkins/agent/rust/rustup-env/toolchains

                                rm -rf ~/.cargo/registry/cache ~/.cargo/registry/index
                                rm -rf ~/.cargo/git/db ~/.cargo/git/checkouts
                                rm -rf ~/.rustup/tmp ~/.rustup/toolchains
                                ln -s /home/jenkins/agent/rust/registry/cache ~/.cargo/registry/cache
                                ln -s /home/jenkins/agent/rust/registry/index ~/.cargo/registry/index
                                ln -s /home/jenkins/agent/rust/git/db ~/.cargo/git/db
                                ln -s /home/jenkins/agent/rust/git/checkouts ~/.cargo/git/checkouts
                                ln -s /home/jenkins/agent/rust/rustup-env/tmp ~/.rustup/tmp
                                ln -s /home/jenkins/agent/rust/rustup-env/toolchains ~/.rustup/toolchains
                            '''
                        }
                    }
                }
                stage('Configure') {
                    steps {
                        sh label: "create build dirs", script: """
                            mkdir -p ${WORKSPACE}/build
                            mkdir -p ${WORKSPACE}/install/tiflash
                        """
                        dir("${WORKSPACE}/build") {
                            sh label: "configure project", script: """
                                cmake '${WORKSPACE}/${REFS.repo}' \\
                                    -G Ninja \\
                                    -DENABLE_FAILPOINTS=true \\
                                    -DCMAKE_BUILD_TYPE=Debug \\
                                    -DCMAKE_PREFIX_PATH='/usr/local' \\
                                    -DCMAKE_INSTALL_PREFIX=${WORKSPACE}/install/tiflash \\
                                    -DENABLE_NEXT_GEN=ON \\
                                    -DENABLE_NEXT_GEN_COLUMNAR=ON \\
                                    -DENABLE_TESTS=false \\
                                    -DUSE_CCACHE=true \\
                                    -DDEBUG_WITHOUT_DEBUG_INFO=true \\
                                    -DUSE_INTERNAL_TIFLASH_PROXY=true \\
                                    -DUSE_INTERNAL_LIBCLARA=true \\
                                    -DRUN_HAVE_STD_REGEX=0
                            """
                        }
                    }
                }
                stage('Build TiFlash') {
                    steps {
                        dir(REFS.repo) {
                            sshagent(credentials: [GIT_CREDENTIALS_ID]) {
                                sh label: "prepare git auth for cargo", script: """
                                    [ -d ~/.ssh ] || mkdir ~/.ssh && chmod 0700 ~/.ssh
                                    ssh-keyscan -t rsa,ecdsa,ed25519 github.com >> ~/.ssh/known_hosts
                                    git config --global url."git@github.com:tidbcloud/cloud-storage-engine.git".insteadOf "https://github.com/tidbcloud/cloud-storage-engine.git"
                                """
                                withEnv(['CARGO_NET_GIT_FETCH_WITH_CLI=true']) {
                                    sh label: "build tiflash", script: """
                                        cmake --build '${WORKSPACE}/build' --target tiflash --parallel 12
                                        cmake --install '${WORKSPACE}/build' --component=tiflash-release --prefix='${WORKSPACE}/install/tiflash'
                                        ccache -s
                                        ls -alh ${WORKSPACE}/install/tiflash
                                    """
                                }
                            }
                        }
                    }
                }
                stage('Prepare Test Workspace') {
                    steps {
                        dir(REFS.repo) {
                            sh label: "stage tiflash binary", script: """
                                mkdir -p tests/.build
                                cp -a ${WORKSPACE}/install/* tests/.build/
                                chown -R 1000:1000 . tests/.build
                                rm -rf .git contrib ${WORKSPACE}/build
                                du -sh . tests/.build || true
                            """
                            cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/${TEST_WORKSPACE_CACHE_FOLDER}") {
                                sh label: "save test workspace", script: """
                                    test -e tests/.build/tiflash
                                    chmod +x tests/fullstack-test-next-gen-columnar/run.sh
                                    test -x tests/fullstack-test-next-gen-columnar/run.sh
                                """
                            }
                        }
                    }
                }
            }
        }

        stage('Integration Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_PATH'
                        values 'fullstack-test-next-gen-columnar'
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE_TEST, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '300Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'docker'
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_path: env.TEST_PATH]) }
                }
                stages {
                    stage('Test') {
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/${TEST_WORKSPACE_CACHE_FOLDER}") {
                                    sh label: "check restored workspace", script: "test -e tests/.build/tiflash"
                                }
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
                                                docker version
                                                docker ps -a

                                                timeout 300 docker pull "${PD_IMAGE}"
                                                timeout 300 docker pull "${TIKV_IMAGE}"
                                                timeout 300 docker pull "${TIDB_IMAGE}"
                                                timeout 300 docker pull "${MINIO_IMAGE}"
                                                timeout 600 docker pull "${TIFLASH_IMAGE}"

                                                rm -rf ~/.docker
                                            '''
                                        }
                                        sh label: "run columnar integration test", script: """#!/usr/bin/env bash
                                            set -o pipefail
                                            chmod +x ./run.sh
                                            TAG=${tiflash_commit_hash} \\
                                            BRANCH=${REFS.base_ref} \\
                                            ENABLE_NEXT_GEN=true \\
                                            ENABLE_NEXT_GEN_COLUMNAR=true \\
                                            ./run.sh 2>&1 | tee columnar-integration-test.log
                                            run_status=\${PIPESTATUS[0]}
                                            if grep -E 'Total failed: [1-9][0-9]* test[(]s[)]' columnar-integration-test.log; then
                                                exit 1
                                            fi
                                            exit \${run_status}
                                        """
                                    }
                                }
                            }
                        }
                        post {
                            unsuccessful {
                                dir("${REFS.repo}/tests/${TEST_PATH}") {
                                    sh label: "archive logs", script: """
                                        docker ps -a || true
                                        if [ -d log ]; then
                                            rm -rf ${TEST_PATH}-log
                                            mv log ${TEST_PATH}-log
                                        fi
                                        if [ -d ${TEST_PATH}-log ]; then
                                            find ${TEST_PATH}-log -name '*.log' -print0 | xargs -0 -r tail -n 500 || true
                                            tar -czf ${TEST_PATH}-logs.tar.gz ${TEST_PATH}-log
                                        else
                                            echo "No logs found." > no-logs.txt
                                            tar -czf ${TEST_PATH}-logs.tar.gz no-logs.txt
                                            rm -f no-logs.txt
                                        fi
                                        chown -R 1000:1000 . || true
                                    """
                                    archiveArtifacts(artifacts: "${TEST_PATH}-logs.tar.gz", allowEmptyArchive: true)
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
