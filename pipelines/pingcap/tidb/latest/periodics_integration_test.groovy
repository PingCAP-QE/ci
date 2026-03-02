@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-periodics_integration_test.yaml'

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
                    container("utils") {
                        sh label: 'download tikv and pd via OCI', script: """
                        mkdir -p third_bin
                        OCI_ARTIFACT_HOST=${OCI_ARTIFACT_HOST} ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --tikv=${TARGET_BRANCH} --pd=${TARGET_BRANCH}
                        mv bin/tikv-server third_bin/ && mv bin/pd-server bin/pd-ctl bin/pd-recover bin/pd-tso-bench bin/pd-api-bench third_bin/ || true
                        rm -rf bin tmp
                        ls -alh third_bin
                        """
                    }
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
                                    sh """
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
        // TODO: add failure notification
        // failure {
        //     // Send lark notification
        // }
    }
}
