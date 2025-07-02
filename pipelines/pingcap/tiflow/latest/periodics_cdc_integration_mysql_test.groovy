// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-periodics_cdc_integration_mysql_test.yaml'
final TARGET_BRANCH = "master"

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
        // parallelsAlwaysFailFast()
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
                dir("tiflow") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tiflow/rev-${TARGET_BRANCH}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tiflow.git', 'tiflow', TARGET_BRANCH, "", trunkBranch=TARGET_BRANCH, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
            }
        }
        stage("prepare") {
            steps {
                dir("third_party_download") {
                    script {
                        retry(2) {
                            sh label: "download third_party", script: """
                                export TIDB_BRANCH="master"
                                export PD_BRANCH="master"
                                export TIKV_BRANCH="master"
                                export TIFLASH_BRANCH="master"
                                cd ../tiflow && ./scripts/download-integration-test-binaries.sh master && ls -alh ./bin
                                make check_third_party_binary
                                cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
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
                    sh label: "prepare", script: """
                        ls -alh ./bin
                        [ -f ./bin/cdc ] || make cdc
                        [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                        [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                        [ -f ./bin/cdc.test ] || make integration_test_build
                        ls -alh ./bin
                        ./bin/cdc version
                    """
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13',
                            'G14', 'G15', 'G16', 'G17', 'G18', 'G19', 'G20', 'G21'
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
                        steps {
                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    timeout(time: 45, unit: 'MINUTES') {
                                        sh label: "${TEST_GROUP}", script: """
                                            rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                            chmod +x ./tests/integration_tests/run_group.sh
                                            ./tests/integration_tests/run_group.sh mysql ${TEST_GROUP}
                                        """
                                    }
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

    post {
        always {
            script {
                def status = currentBuild.result ?: 'SUCCESS'
                def title = "CDC MySQL Integration Test: ${status}"
                def content = "CDC MySQL Integration Test: ${status}\\n" +
                    "Branch: ${TARGET_BRANCH}\\n" +
                    "Build URL: ${BUILD_URL}\\n" +
                    "Job Page: https://prow.tidb.net/?repo=pingcap%2Ftiflow&type=periodic\\n"

                withCredentials([string(credentialsId: 'cdc-feishu-webhook-url', variable: 'WEBHOOK_URL')]) {
                    sh """
                        curl -X POST ${WEBHOOK_URL} -H 'Content-Type: application/json' \
                        -d '{
                            "msg_type": "text",
                            "content": {
                              "text": "$content"
                            }
                        }'
                    """
                }
            }
        }
    }
}
