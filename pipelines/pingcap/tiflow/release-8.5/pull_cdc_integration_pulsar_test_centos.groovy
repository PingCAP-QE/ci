// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-8.5/pod-pull_cdc_integration_pulsar_test.yaml'
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
                        sh label: "download third_party", script: """
                            cd ../tiflow && ./scripts/download-integration-test-binaries.sh ${REFS.base_ref} && ls -alh ./bin
                            make check_third_party_binary
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            rm -rf bin/pd-* bin/tikv-* bin/tiflash bin/lib* bin/tidb*

                            wget -q -O tikv-server.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/tikv/tikv/package?tag=v8.5.2-pre_linux_amd64&file=tikv-v8.5.2-pre-linux-amd64.tar.gz"
                            wget -q -O pd-server.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/tikv/pd/package?tag=v8.5.2-pre_linux_amd64&file=pd-v8.5.2-pre-linux-amd64.tar.gz"
                            wget -q -O pd-ctl.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/tikv/pd/package?tag=v8.5.2-pre_linux_amd64&file=pd-ctl-v8.5.2-pre-linux-amd64.tar.gz"
                            wget -q -O tidb-server.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/pingcap/tidb/package?tag=v8.5.2-pre_linux_amd64&file=tidb-v8.5.2-pre-linux-amd64.tar.gz"
                            wget -q -O tiflash.tar.gz "https://internal-do.pingcap.net/dl/oci-file/hub.pingcap.net/pingcap/tiflash/package?tag=v8.5.2-pre_linux_amd64&file=tiflash-v8.5.2-pre-linux-amd64.tar.gz"
                            tar xzf tikv-server.tar.gz -C bin
                            tar xzf pd-server.tar.gz -C bin
                            tar xzf pd-ctl.tar.gz -C bin
                            tar xzf tidb-server.tar.gz -C bin
                            tar xzf tiflash.tar.gz && mv tiflash/* bin/ && rm -rf tiflash/
                            rm -rf tikv-server.tar.gz pd-server.tar.gz pd-ctl.tar.gz tidb-server.tar.gz tiflash.tar.gz

                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiflash --version
                            ./bin/sync_diff_inspector --version
                        """
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-pulsar-test')) {
                        // build cdc, pulsar_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_pulsar_consumer ] || make pulsar_consumer
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09',
                            'G10', 'G11', 'G12', 'G13', 'G14', 'G15', 'G16', 'G17'
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
                        options { timeout(time: 40, unit: 'MINUTES') }
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    sh label: "${TEST_GROUP}", script: """
                                        rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                        chmod +x ./tests/integration_tests/run_group.sh
                                        ./tests/integration_tests/run_group.sh pulsar ${TEST_GROUP}
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
