@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final FILESERVER_URL = 'http://fileserver.pingcap.net'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-periodics_integration_test.yaml'

final tikv_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/tikv/${TARGET_BRANCH}/sha1"
final pd_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/pd/${TARGET_BRANCH}/sha1"
final tidb_sha1_url = "${FILESERVER_URL}/download/refs/pingcap/tidb/${TARGET_BRANCH}/sha1"

final tikv_sha1_verify_path = "refs/pingcap/tikv/${TARGET_BRANCH}/sha1.verify"
final pd_sha1_verify_path = "refs/pingcap/pd/${TARGET_BRANCH}/sha1.verify"
final tidb_sha1_verify_path = "refs/pingcap/tidb/${TARGET_BRANCH}/sha1.verify"

def tikv_commit_sha = ""
def pd_commit_sha = ""
def tidb_commit_sha = ""

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
                }
            }
        }
        stage("Download") {
            steps {
                dir('download') {
                    script {
                      tikv_commit_sha = sh(returnStdout: true, script: "curl ${tikv_sha1_url}").trim()
                      pd_commit_sha = sh(returnStdout: true, script: "curl ${pd_sha1_url}").trim()
                      tidb_commit_sha = sh(returnStdout: true, script: "curl ${tidb_sha1_url}").trim()
                    }
                    sh """
                    tikv_download_url="${FILESERVER_URL}/download/builds/pingcap/tikv/${tikv_commit_sha}/centos7/tikv-server.tar.gz"
                    pd_download_url="${FILESERVER_URL}/download/builds/pingcap/pd/${pd_commit_sha}/centos7/pd-server.tar.gz"

                    mkdir -p tmp
                    mkdir -p third_bin
                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "tmp/tikv-server.tar.gz" \${tikv_download_url}
                    tar -xz -C third_bin bin/tikv-server -f tmp/tikv-server.tar.gz && mv third_bin/bin/tikv-server third_bin/
                    wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O "tmp/pd-server.tar.gz" \${pd_download_url}
                    tar -xz -C third_bin 'bin/*' -f tmp/pd-server.tar.gz && mv third_bin/bin/* third_bin/
                    rm -rf third_bin/bin
                    ls -alh third_bin
                    """
                }
            }
        }
        stage('Checkout') {
            steps {
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${TARGET_BRANCH}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', TARGET_BRANCH, "", trunkBranch=TARGET_BRANCH, timeout=5, credentialsId="")
                                sh label: "checkout tidb code", script: """
                                    git status
                                    git fetch origin ${TARGET_BRANCH}:local_${TARGET_BRANCH}
                                    git checkout local_${TARGET_BRANCH}
                                    git checkout -f ${tidb_commit_sha}
                                    git status -s
                                """
                            }
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                  dir('tidb') {
                        cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                            sh """
                            make
                            cp bin/tidb-server bin/integration_test_tidb-server
                            """
                            sh label: "prepare all binaries", script: """
                            touch rev-${tidb_commit_sha}
                            cp -f ../download/third_bin/* bin/
                            chmod +x bin/*
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
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
                            'integrationtest_with_tikv.sh y',
                            'integrationtest_with_tikv.sh n',
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
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    // will fail when not found in cache or no cached
                                    sh """
                                    ls -l rev-${tidb_commit_sha}
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
                                    sh """
                                    find . -name "pd*.log" -type f -exec tail -n 500 '{}' +
                                    find . -name "tikv*.log" -type f -exec tail -n 500 '{}' +
                                    find . -name "*.out" -type f -exec tail -n 500 '{}' +
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
    post {
        success {
            // Send lark notification
            // update sha1.verify file success
            sh """
            echo ${tikv_commit_sha} > tikv.sha1.verify
            echo ${pd_commit_sha} > pd.sha1.verify
            echo ${tidb_commit_sha} > tidb.sha1.verify
            curl -F ${tikv_sha1_verify_path}=@tikv.sha1.verify ${FILE_SERVER_URL}/upload
            curl -F ${pd_sha1_verify_path}=@pd.sha1.verify ${FILE_SERVER_URL}/upload
            curl -F ${tidb_sha1_verify_path}=@tidb.sha1.verify ${FILE_SERVER_URL}/upload
            """
        }

        // TODO: add failure notification
        // failure {
        //     // Send lark notification
        // }
    }
}
