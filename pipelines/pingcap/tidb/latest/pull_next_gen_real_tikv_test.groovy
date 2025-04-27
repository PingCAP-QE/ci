// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_next_gen_real_tikv_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final TARGET_BRANCH_PD = "master"
final TARGET_BRANCH_TIKV = "dedicated"

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 65, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("pd") {
                    cache(path: "./", includes: '**/*', key: "git/tikv/pd/rev-${TARGET_BRANCH_PD}", restoreKeys: ['git/tikv/pd/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/tikv/pd.git', 'pd', TARGET_BRANCH_PD, "", trunkBranch=TARGET_BRANCH_PD, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tikv") {
                    cache(path: "./", includes: '**/*', key: "git/tidbcloud/cloud-storage-engine/rev-${TARGET_BRANCH_TIKV}", restoreKeys: ['git/tidbcloud/cloud-storage-engine/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutSupportBatch('git@github.com:tidbcloud/cloud-storage-engine.git', 'tikv', TARGET_BRANCH_TIKV, "", [], GIT_CREDENTIALS_ID)
                            }   
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                dir('tikv') {
                    container('rust') {
                        // Cache cargo registry and git dependencies
                        // Use TARGET_BRANCH_TIKV and the pipeline file name in the key for better scoping
                        script {
                            def pipelineIdentifier = env.JOB_BASE_NAME ?: "pull-next-gen-real-tikv-test"
                            // Clean workspace .cargo directory before attempting cache restore/build
                            sh "rm -rf .cargo"
                            // Cache paths relative to the current workspace directory ('tikv')
                            cache(path: ".cargo/git", key: "cargo-git-ws-tikv-next-gen-${TARGET_BRANCH_TIKV}-${pipelineIdentifier}") {
                                sh label: "build tikv", script: """
                                    set -e
                                    source /opt/rh/devtoolset-8/enable
                                    cmake --version
                                    protoc --version
                                    gcc --version

                                    # Set CARGO_HOME to a path within the workspace for caching
                                    export CARGO_HOME="\$(pwd)/.cargo"
                                    echo "Set CARGO_HOME to: \${CARGO_HOME}"

                                    mkdir -vp "\${CARGO_HOME}"

                                    # Create cargo config inside the workspace CARGO_HOME
                                    cat << EOF | tee "\${CARGO_HOME}/config.toml"
[source.crates-io]
replace-with = 'aliyun'
[source.aliyun]
registry = "sparse+https://mirrors.aliyun.com/crates.io-index/"
EOF
                                    echo "Current CARGO_HOME is: \${CARGO_HOME}"
                                    echo "Listing cached directories before build (relative path):"
                                    ls -lad "\${CARGO_HOME}/registry" || echo "Registry cache miss or empty."
                                    ls -lad "\${CARGO_HOME}/git" || echo "Git cache miss or empty."

                                    make release

                                    echo "Listing cached directories after build (relative path):"
                                    du -sh "\${CARGO_HOME}/registry" || echo "Registry dir not found."
                                    du -sh "\${CARGO_HOME}/git" || echo "Git dir not found."

                                    # Ensure all filesystem writes are flushed before cache archiving begins
                                    sync
                                """
                            }
                        }
                    }
                }
                dir('pd') {
                    sh label: "build pd", script: 'make build'
                } 
                dir('tidb') {
                    sh label: 'next-gen tidb-server', script: 'NEXT_GEN=1 make server'

                    // script {
                    //      component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                    //      component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                    // }

                    // cache it for other pods
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh label: 'copy pd and tikv-server', script: """
                            cp ../pd/bin/pd-server ./bin/
                            cp ../tikv/target/release/tikv-server ./bin/
                            bin/tidb-server -V
                            bin/tikv-server -V
                            bin/pd-server -V
                            mv bin/tidb-server bin/integration_test_tidb-server
                            touch rev-${REFS.pulls[0].sha}
                        """
                    }
                }
                
            }
        }
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'run_real_tikv_tests.sh bazel_brietest',
                            'run_real_tikv_tests.sh bazel_pessimistictest',
                            'run_real_tikv_tests.sh bazel_sessiontest',
                            'run_real_tikv_tests.sh bazel_statisticstest',
                            'run_real_tikv_tests.sh bazel_txntest',
                            'run_real_tikv_tests.sh bazel_addindextest',
                            'run_real_tikv_tests.sh bazel_addindextest1',
                            'run_real_tikv_tests.sh bazel_addindextest2',
                            'run_real_tikv_tests.sh bazel_addindextest3',
                            'run_real_tikv_tests.sh bazel_addindextest4',
                            'run_real_tikv_tests.sh bazel_importintotest',
                            'run_real_tikv_tests.sh bazel_importintotest2',
                            'run_real_tikv_tests.sh bazel_importintotest3',
                            'run_real_tikv_tests.sh bazel_importintotest4',
                            'run_real_tikv_tests.sh bazel_pipelineddmltest',
                            'run_real_tikv_tests.sh bazel_flashbacktest',
                        )
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage('Test')  {
                        options { timeout(time: 50, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${REFS.pulls[0].sha}" // will fail when not found in cache or no cached.
                                }

                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh """
                                sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                                git diff .
                                git status
                                """
                                sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                            }
                        }
                        post {
                            always {
                                dir('tidb') {
                                    // archive test report to Jenkins.
                                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                                }
                            }
                            unsuccessful {
                                dir("tidb") {
                                    sh label: "archive log", script: """
                                    str="$SCRIPT_AND_ARGS"
                                    logs_dir="logs_\${str// /_}"
                                    mkdir -p \${logs_dir}
                                    mv pd*.log \${logs_dir} || true
                                    mv tikv*.log \${logs_dir} || true
                                    mv tests/integrationtest/integration-test.out \${logs_dir} || true
                                    tar -czvf \${logs_dir}.tar.gz \${logs_dir} || true
                                    """
                                    archiveArtifacts(artifacts: '*.tar.gz', allowEmptyArchive: true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
