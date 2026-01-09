// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_tiflash_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
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
    }
    stages {
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
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'vector-search-test')) {
                        script {
                            component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                            component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin', trunkBranch="master", artifactVerify=true)
                            // Note tiflash need extract all files in tiflash dir (extract tar.gz to tiflash dir)
                            component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tiflash', REFS.base_ref, REFS.pulls[0].title, 'centos7/tiflash.tar.gz', '', trunkBranch="master", artifactVerify=false, useBranchInArtifactUrl=true)
                            sh label: 'move tiflash', script: 'mv tiflash/* bin/ && rm -rf tiflash'
                        }
                    }
                }
                dir('test-assets') {
                    cache(path: "./", includes: '**/*', key: "test-assets/tidb/vector-search-test/euclidean-hdf5") {
                        sh label: 'download test assets', script: """
                        # wget https://ann-benchmarks.com/fashion-mnist-784-euclidean.hdf5
                        # wget https://ann-benchmarks.com/mnist-784-euclidean.hdf5
                        # Use internal file server to download test assets
                        wget -q ${FILE_SERVER_URL}/download/ci-artifacts/tidb/vector-search-test/v20250521/fashion-mnist-784-euclidean.hdf5
                        wget -q ${FILE_SERVER_URL}/download/ci-artifacts/tidb/vector-search-test/v20250521/mnist-784-euclidean.hdf5
                        """
                    }
                }
                dir('v8.5.1') {
                    cache(path: "./", includes: '**/*', key: "test-assets/tidb/vector-search-test/tiup-v8.5.1") {
                        sh label: 'download v8.5.1 if not cached', script: """
                            # Only download if components directory doesn't exist (cache miss)
                            if [ ! -d "components" ]; then
                                echo "Cache miss, downloading from tiup..."
                                curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
                                export PATH="\$HOME/.tiup/bin:\$PATH"
                                tiup install tidb v8.5.1
                                tiup install pd v8.5.1
                                tiup install tikv v8.5.1
                                tiup install tiflash v8.5.1
                                cp -r ~/.tiup/components ./
                            else
                                echo "Cache hit, using cached components"
                            fi
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_SCRIPT'
                        values 'run_mysql_tester.sh', 'run_python_tester.sh', 'run_upgrade_test.sh'
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
                    stage('Restore cache') {
                        steps {
                            dir("tidb") {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    sh 'ls -lh'
                                }
                            }
                            dir('tidb') {
                                cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'vector-search-test')) {
                                    sh label: 'print version', script: """
                                        bin/tidb-server -V
                                        bin/tikv-server -V
                                        bin/pd-server -V
                                        bin/tiflash --version
                                    """
                                }
                            }
                            dir('test-assets') {
                                cache(path: "./", includes: '**/*', key: "test-assets/tidb/vector-search-test/euclidean-hdf5") {
                                sh label: 'print assets', script: """
                                        ls -alh
                                    """
                                }
                            }
                            dir('v8.5.1') {
                                cache(path: "./", includes: '**/*', key: "test-assets/tidb/vector-search-test/tiup-v8.5.1") {
                                    sh label: 'print v8.5.1', script: """
                                        ls -alh
                                        curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
                                        export PATH="\$HOME/.tiup/bin:\$PATH"
                                        cp -r components ~/.tiup/
                                        tiup pd:v8.5.1 --version
                                        tiup tikv:v8.5.1 --version
                                        tiup tiflash:v8.5.1 --version
                                    """
                                }
                            }
                        }
                    }
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                sh label: "TEST_SCRIPT ${TEST_SCRIPT}", script: """#!/usr/bin/env bash
                                    export PATH="\$HOME/.tiup/bin:\$PATH"
                                    export PATH="\$HOME/.local/bin:\$PATH"
                                    curl --proto '=https' --tlsv1.2 -LsSf https://github.com/astral-sh/uv/releases/download/0.7.3/uv-installer.sh | sh

                                    export ASSETS_DIR=\$(pwd)/../test-assets
                                    cd tests/clusterintegrationtest/

                                    uv venv --python python3.9
                                    source .venv/bin/activate
                                    uv pip install -r requirements.txt
                                    ./${TEST_SCRIPT}
                                """
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                    archiveArtifacts artifacts: 'tidb/tests/clusterintegrationtest/logs', fingerprint: true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
