// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-9.0-beta branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-9.0-beta/pod-pull_cdc_integration_kafka_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
def skipRemainingStages = false

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
        timeout(time: 65, unit: 'MINUTES')
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
                    script {
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
        stage('Check diff files') {
            steps {
                container("golang") {
                    script {
                        def pr_diff_files = component.getPrDiffFiles(GIT_FULL_REPO_NAME, REFS.pulls[0].number, GIT_CREDENTIALS_ID2)
                        def pattern = /(^dm\/|^engine\/).*$/
                        println "pr_diff_files: ${pr_diff_files}"
                        // if all diff files start with dm/, skip cdc integration test
                        def matched = component.patternMatchAllFiles(pattern, pr_diff_files)
                        if (matched) {
                            println "matched, all diff files full path start with dm/ or engine/, current pr is dm/engine's pr(not related to ticdc), skip cdc integration test"
                            currentBuild.result = 'SUCCESS'
                            skipRemainingStages = true
                            return
                        }
                    }
                }
            }
        }
        stage('Checkout') {
            when { expression { !skipRemainingStages} }
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
            when { expression { !skipRemainingStages} }
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        script {
                            def branchInfo = component.extractHotfixInfo(REFS.base_ref)

                            sh label: "download third_party", script: """
                                mkdir -p bin
                                cd ../tiflow

                                if [[ "${branchInfo.isHotfix}" == "true" ]]; then
                                    echo "Hotfix version tag: ${branchInfo.versionTag}"
                                    echo "This is a hotfix branch, downloading exact version ${branchInfo.versionTag} binaries"

                                    # First download binary using the release branch script
                                    ./scripts/download-integration-test-binaries.sh ${REFS.base_ref}
                                    # remove binarys of tidb-server, pd-server, tikv-server, tiflash
                                    rm -rf bin/tidb-server bin/pd-* bin/tikv-server bin/tiflash bin/lib*

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
                                    ./scripts/download-integration-test-binaries.sh ${REFS.base_ref}
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
                            """
                        }
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-test')) {
                        // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                            [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
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
            when { expression { !skipRemainingStages} }
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08',
                            'G09', 'G10', 'G11', 'G12', 'G13', 'G14', 'G15', 'G16', 'G17'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
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
                                        chmod +x ./tests/integration_tests/run_group.sh
                                        ./tests/integration_tests/run_group.sh kafka ${TEST_GROUP}
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
