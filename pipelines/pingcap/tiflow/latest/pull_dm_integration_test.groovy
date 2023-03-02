// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
// @Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_integration_test.yaml'

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
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("prepare") {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    retry(2) {
                        sh label: "download third_party", script: """
                            cd ../tiflow && ./dm/tests/download-integration-test-binaries.sh master && ls -alh ./bin
                            make check_third_party_binary
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                        """
                    }
                }
                dir("tiflow") {
                    cache(path: "./bin", filter: '**/*', key: "git/pingcap/tiflow/dm-integration-test-binarys-${ghprbActualCommit}") { 
                        // build dm-master.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/dm-master.test ] || make make dm_integration_test_build
                            ls -alh ./bin
                            which ./bin/dm-master.test
                            which ./bin/dm-syncer.test
                            which ./bin/dm-master.test
                            which ./bin/dm-master.test
                        """
                    }
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") { 
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
                        values 
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
                        environment { 
                            DM_CODECOV_TOKEN = credentials('codecov-token-tiflow') 
                            DM_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')    
                        }
                        steps {
                            dir('tiflow') {
                                timeout(time: 5, unit: 'MINUTES') {
                                    container("mysql1") {
                                        sh label: "wait mysql ready", script: """
                                            export MYSQL_PORT="3306"
                                            ./dm/tests/wait_for_mysql.sh
                                            export MYSQL_PORT="3307"
                                            ./dm/tests/wait_for_mysql.sh
                                        """
                                    }
                                    
                                }
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") {
                                    sh label: "${TEST_GROUP}", script: """
                                        export PATH=/usr/local/go/bin:\$PATH
                                        make dm_integration_test CASE="${TEST_GROUP}"  
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                     
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
