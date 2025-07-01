// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-7.5/pod-pull_br_integration_test.yaml'
final TARGET_BRANCH = "release-7.5"

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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
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
        stage('Prepare') {
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            chmod +x ../tidb/br/tests/*.sh
                            ${WORKSPACE}/tidb/br/tests/download_integration_test_binaries.sh release-7.5
                            mkdir -p bin && mv third_bin/* bin/
                            ls -alh bin/
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiflash --version
                        """
                    }
                }
                dir('tidb') {
                    sh label: "check all tests added to group", script: """
                        #!/usr/bin/env bash
                        chmod +x br/tests/*.sh
                        ./br/tests/run_group.sh others
                    """
                    // build br.test for integration test
                    // only build binarys if not exist, use the cached binarys if exist
                    sh label: "prepare", script: """
                        [ -f ./bin/tidb-server ] || (make failpoint-enable && make && make failpoint-disable)
                        [ -f ./bin/br.test ] || make build_for_br_integration_test
                        ls -alh ./bin
                        ./bin/tidb-server -V
                    """
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/br-lightning") {
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
                            'G14', 'G15', 'G16', 'G17'
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
                    stage("Test") {
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/br-lightning") {
                                    sh label: "TEST_GROUP ${TEST_GROUP}", script: """
                                        #!/usr/bin/env bash
                                        chmod +x br/tests/*.sh
                                        ./br/tests/run_group.sh ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/backup_restore_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/backup_restore_test/ -type f -name "*.log")
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
        failure {
            script {
                def status = currentBuild.result ?: 'SUCCESS'
                def title = "Daily BR Integration Test: ${status}"
                def content = "Daily BR Integration Test: ${status}\\n" +
                    "Branch: ${TARGET_BRANCH}\\n" +
                    "Build URL: ${RUN_DISPLAY_URL}\\n" +
                    "Job Page: https://prow.tidb.net/?repo=pingcap%2Ftidb&type=periodic&job=*periodics_br_*\\n"

                withCredentials([string(credentialsId: 'daily-br-integration-test-feishu-webhook-url', variable: 'WEBHOOK_URL')]) {
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
