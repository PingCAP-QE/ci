// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_BRANCH = 'master'
final GIT_COMMIT = '9743a9a2d2c626acbd7e13d4693cca9c58f329b7'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb/latest/pod-merged_integration_cdc_test.yaml'

// TODO(wuhuizuo): tidb-test should delivered by docker image.
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
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_COMMIT ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git",
                                    ]],
                                ]
                            )
                        }
                    }
                }
                dir("tiflow") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tiflow/rev-${GIT_BRANCH}", restoreKeys: ['git/pingcap/tiflow/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_BRANCH ]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 15],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/heads/*:refs/remotes/origin/*",
                                        url: "https://github.com/pingcap/tiflow.git",
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
                    sh "git branch && git status"
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${GIT_COMMIT}") {
                        // FIXME: https://github.com/pingcap/tidb-test/issues/1987
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make'
                    }
                }
                dir('tiflow') {
                    sh "git branch && git status"
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow") {
                        sh 'touch ws-${BUILD_TAG}'
                        sh label: 'prepare cdc binary', script: """
                        make cdc
                        make integration_test_build
                        make kafka_consumer
                        make check_failpoint_ctl
                        ls bin/
                        """
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'CASES'
                        values 'region_merge', 'ddl_reentrant'
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
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${GIT_BRANCH}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                }
                            }
                            dir('tiflow') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tiflow") {
                                    sh 'chmod +x ../scripts/pingcap/tiflow/*.sh'
                                    sh "${WORKSPACE}/scripts/pingcap/tiflow/ticdc_integration_test_download_dependency.sh master master master master http://fileserver.pingcap.net"
                                    sh label: "Case ${CASES}", script: """
                                    mv third_bin/* bin/ && ls -alh bin/
                                    rm -rf /tmp/tidb_cdc_test
                                    mkdir -p /tmp/tidb_cdc_test
                                    cp ../tidb/bin/tidb-server ./bin/
                                    ./bin/tidb-server -V
                                    ls -alh ./bin/
                                    make integration_test_mysql CASE="${CASES}"
                                    """             
                                }
                            }
                        }
                        post{
                            failure {
                                println "Test failed, archive the log"
                                // def log_tar_name = "${CASES}".replaceAll("\\s","-")
                                // sh label: "archive failure logs", script: """
                                // ls /tmp/tidb_cdc_test/
                                // tar -cvzf log-${log_tar_name}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")    
                                // ls -alh  log-${log_tar_name}.tar.gz  
                                // """
                                // archiveArtifacts(artifacts: "log-${log_tar_name}.tar.gz", caseSensitive: false)
                            }
                        }
                    }
                }
            }        
        }
    }
}