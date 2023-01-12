// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb-test/latest/pod-ghpr_integration_mysql_test.yaml'

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
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
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
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbTargetBranch]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/pingcap/tidb.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        credentialsId: GIT_CREDENTIALS_ID,
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "git@github.com:${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-server") {
                            sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                            sh label: 'download binary', script: """
                                chmod +x ${WORKSPACE}/scripts/pingcap/tidb-test/*.sh
                                ${WORKSPACE}/scripts/pingcap/tidb-test/download_pingcap_artifact.sh --pd=${ghprbTargetBranch} --tikv=${ghprbTargetBranch}
                                mv third_bin/* bin/
                                ls -alh bin/
                            """
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", filter: '**/*', key: "binary/pingcap/tidb-test/rev-${ghprbActualCommit}") {
                        sh 'touch ws-${BUILD_TAG}'
                    }
                }
            }
        }
        stage('MySQL Tests') {
            matrix {
                axes {
                    axis {
                        name 'PART'
                        values '1', '2', '3', '4'
                    }
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
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
                        options { timeout(time: 25, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./bin", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-server") {
                                    sh label: "print version", script: """
                                        ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V
                                        ls bin/pd-server && chmod +x bin/pd-server && ./bin/pd-server -V
                                        ls bin/tikv-server && chmod +x bin/tikv-server && ./bin/tikv-server -V
                                    """
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "binary/pingcap/tidb-test/rev-${ghprbActualCommit}") {
                                    dir('mysql_test') {
                                        sh label: "PART ${PART},CACHE_ENABLED ${CACHE_ENABLED}", script: """
                                            #!/usr/bin/env bash
                                            echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                            bash ${WORKSPACE}/scripts/pingcap/tidb-test/start_tikv.sh
                                            export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/bin/tidb-server"
                                            export CACHE_ENABLED=${CACHE_ENABLED}
                                            export TIKV_PATH="127.0.0.1:2379"
                                            export TIDB_TEST_STORE_NAME="tikv"
                                            cd mysql_test/ && ./test.sh -blacklist=1 -part=${PART}
                                        """
                                    }
                                }
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                        }
                    }
                }
            }        
        }
    }
}
