// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflash"
final GIT_FULL_REPO_NAME = 'pingcap/tiflash'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflash/latest/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

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
        timeout(time: 40, unit: 'MINUTES')
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
                dir("tiflash") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage("Build") {
            stages("prepare tools") {
                stage("CCache") {

                }
                stage("Cmake") {

                }
                stage("Clang-Format") {

                }
                stage("Clang-Format-15") {

                }
                stage("Clang-Tidy") {

                }
                stage("Coverage") {

                }
            }
            stages("Prepare Cache") {
                stage("CCache") {}
                stage("Proxy Cache") {}
            }
            stages("Build Dependency and Utils") {
                stage("Cluster Manage") {}
                stage("TiFlash Proxy") {}
            }
            stage("Configure Project") {

            }
            stage("Format Check") {

            }
            stages("Build TiFlash") {

            }
            stages("Post Build") {
                stage("Mark Toolchiain"){}
                stage("Static Analysis"){}
                stage("Upload Build Artifacts") {}
                stage("Upload Build Data") {}
                stage("Upload CCache") {}
                stage("Upload Proxy") {}
            }
        }
        stage('Tests') {
            stages {
                stage("Test") {
                    options { timeout(time: 40, unit: 'MINUTES') }
                    steps {
                        dir('tiflash') {
                            cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS)) {
                                sh label: "${TEST_CMD}", script: """
                                    make ${TEST_CMD}
                                """
                            }
                        }
                    }
                    post {
                        always {
                            junit(testResults: "**/tiflash/*-junit-report.xml", allowEmptyResults : true)  
                        }
                    }
                }
            }     
        }
    }
}
