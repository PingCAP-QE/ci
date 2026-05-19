// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-6.1/pod-pull_cdc_integration_kafka_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 65, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
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
        stage("prepare") {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        script {
                            def branchInfo = component.extractHotfixInfo(REFS.base_ref)

                            sh label: "download third_party", script: """
                                mkdir -p bin
                                cd ../tiflow
                                cp ${WORKSPACE}/scripts/pingcap/tiflow/release-6.1/ticdc_download_integration_test_binaries.sh ./
                                chmod +x ticdc_download_integration_test_binaries.sh

                                if [[ "${branchInfo.isHotfix}" == "true" ]]; then
                                    echo "Hotfix version tag: ${branchInfo.versionTag}"
                                    echo "This is a hotfix branch, downloading exact version ${branchInfo.versionTag} binaries"

                                    # First download binary using the release branch script
                                    ./ticdc_download_integration_test_binaries.sh
                                    # remove binarys of tidb-server, pd-server, tikv-server, tiflash
                                    rm -rf bin/tidb-server bin/pd-* bin/tikv-server bin/tiflash bin/tiflash_dir bin/lib*

                                    # Then download and replace other components with exact versions
                                    cp ../scripts/pingcap/tiflow/download_test_binaries_by_tag.sh ./
                                    chmod +x download_test_binaries_by_tag.sh

                                    # Save sync_diff_inspector and some other binaries
                                    mv bin tmp_bin

                                    # Download exact versions of tidb-server, pd-server, tikv-server, tiflash
                                    ./download_test_binaries_by_tag.sh ${branchInfo.versionTag}

                                    # Restore some binaries
                                    mv tmp_bin/* bin/ && rm -rf tmp_bin
                                else
                                    echo "Release branch, downloading binaries from ${REFS.base_ref}"
                                    ./ticdc_download_integration_test_binaries.sh
                                fi

                                make check_third_party_binary
                                cd - && mv ../tiflow/bin/* ./bin/

                                # Verify all required binaries
                                echo "Verifying downloaded binaries..."
                                ls -alh ./bin
                                ./bin/tidb-server -V
                                ./bin/pd-server -V
                                ./bin/tikv-server -V
                                ./bin/tiflash --version
                                ./bin/sync_diff_inspector --version
                            """
                        }
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-test')) {
                        // build cdc, kafka_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                            [ -f ./bin/cdc.test ] || make integration_test_build
                            ls -alh ./bin
                            ./bin/cdc version
                        """
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G0', 'G1', 'G2', 'G3', 'G4', 'G5', 'G6',  'G7', 'G8', 'G9', 'G10', 'G11', 'G12', 'G13',
                            'G14', 'G15'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 45, unit: 'MINUTES') }
                        environment {
                            TICDC_CODECOV_TOKEN = credentials('codecov-token-tiflow')
                            TICDC_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')
                        }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    container("kafka") {
                                        timeout(time: 6, unit: 'MINUTES') {
                                            sh label: "Waiting for kafka ready", script: """
                                                echo "Waiting for zookeeper to be ready..."
                                                while ! nc -z localhost 2181; do sleep 10; done
                                                echo "Waiting for kafka to be ready..."
                                                while ! nc -z localhost 9092; do sleep 10; done
                                                echo "Waiting for kafka-broker to be ready..."
                                                while ! echo dump | nc localhost 2181 | grep brokers | awk '{\$1=\$1;print}' | grep -F -w "/brokers/ids/1"; do sleep 10; done
                                            """
                                        }
                                    }
                                    sh label: "${TEST_GROUP}", script: """
                                        rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                        chmod +x ../scripts/pingcap/tiflow/release-6.1/cdc_run_group.sh
                                        cp ../scripts/pingcap/tiflow/release-6.1/cdc_run_group.sh tests/integration_tests/
                                        ln -s ${WORKSPACE}/tiflow/bin ${WORKSPACE}/tiflow/tests/bin
                                        ./tests/integration_tests/cdc_run_group.sh kafka ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tidb_cdc_test/
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }
}
