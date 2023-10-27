// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/tikv'
final FILESERVER_URL = 'http://fileserver.pingcap.net'
final POD_TEMPLATE_FILE = 'pipelines/tikv/tikv/latest/pod-pull_integration_with_tidb.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

def tidb_commit_sha = ""
def tidb_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/tidb/${REFS.base_ref}/sha1"

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                script {
                    tidb_commit_sha = sh(returnStdout: true, script: "curl ${tidb_sha1_url}").trim()
                }
                dir("tikv") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${tidb_commit_sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, '', '')
                                sh label: "checkout tidb code", script: """
                                git status
                                git fetch origin ${REFS.base_ref}:local_${REFS.base_ref}
                                git checkout local_${REFS.base_ref}
                                git checkout -f ${tidb_commit_sha}
                                """
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tikv') {
                    container("rust") {
                        sh label: 'tikv-server', script: """
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                        CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
                        """
                        sh label: 'third-party', script: """
                        chmod +x ${WORKSPACE}/scripts/artifacts/*.sh
                        ${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --pd=${REFS.base_ref}
                        tidb_download_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_commit_sha}/centos7/tidb-server.tar.gz"
                        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "tmp/tidb-server.tar.gz" \${tidb_download_url}
                        tar -xz -C third_bin bin/tidb-server -f tmp/tidb-server.tar.gz && mv third_bin/bin/tidb-server third_bin/
                        rm -rf third_bin/bin && mv third_bin/* bin/ && ls -alh bin/
                        """
                    }
                }
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") { 
                        sh label: "prepare all binaries", script: """
                        touch rev-${tidb_commit_sha}
                        cp -f ../tikv/bin/*  bin/
                        chmod +x bin/*
                        ./bin/tikv-server -V
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        """     
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'integrationtest_with_tikv.sh y',
                            'integrationtest_with_tikv.sh n',
                            'run_real_tikv_tests.sh bazel_brietest',
                            'run_real_tikv_tests.sh bazel_pessimistictest',
                            'run_real_tikv_tests.sh bazel_sessiontest',
                            'run_real_tikv_tests.sh bazel_statisticstest',
                            'run_real_tikv_tests.sh bazel_txntest',
                            'run_real_tikv_tests.sh bazel_addindextest',
                            'run_real_tikv_tests.sh bazel_importintotest',
                            'run_real_tikv_tests.sh bazel_importintotest2',
                            'run_real_tikv_tests.sh bazel_importintotest3',
                            'run_real_tikv_tests.sh bazel_importintotest4',
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
                        options { timeout(time: 60, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                                    // will fail when not found in cache or no cached
                                    sh """   
                                    ls -l rev-${tidb_commit_sha}
                                    cp bin/tidb-server bin/integration_test_tidb-server
                                    ./bin/integration_test_tidb-server -V
                                    ./bin/pd-server -V
                                    ./bin/tikv-server -V
                                    """
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
                            failure {
                                dir("tidb") {
                                    sh """
                                    find . -name "pd*.log" -type f -exec tail '{}' +
                                    find . -name "tikv*.log" -type f -exec tail '{}' +
                                    find . -name "*.out" -type f -exec tail '{}' +
                                    """
                                    archiveArtifacts(artifacts: 'pd*.log, tikv*.log, tests/integrationtest/integration-test.out', allowEmptyArchive: true)
                                }
                            }
                        }
                    }
                }
            }
        } 
    }
}
