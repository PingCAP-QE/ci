// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_unit-test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
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
    options {
        timeout(time: 90, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
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

                            sh """
                            chown 1000:1000 -R ./
                            """
                        }
                    }
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
            // TODO: need to simplify this part, all config and build logic should be in script in tiflash repo
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
                            -DENABLE_TESTS=true \\
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
        stage("Build TiFlash") {
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

        stage("Unit Test Prepare") {
            steps {
                script {
                    dir("${WORKSPACE}/tiflash") {
                        sh label: "change permission", script: """
                            chown -R 1000:1000 ./
                        """
                        sh label: "copy build artifacts", script: """
                            git status
                            git show --oneline -s
                            rm -rf tests/.build
                            mkdir -p tests/.build
                            cp -r ${WORKSPACE}/install/* tests/.build/
                            ls -alh tests/.build/
                            du -sh tests/.build/
                        """
                    }
                }
                sh label: "link tiflash and tests", script: """
                ls -lha ${WORKSPACE}/tiflash
                ln -sf ${WORKSPACE}/tiflash/tests/.build/tiflash /tiflash
                ln -sf ${WORKSPACE}/tiflash/tests /tests
                """
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
