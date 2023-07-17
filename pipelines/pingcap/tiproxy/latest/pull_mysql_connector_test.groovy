// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiproxy/latest/pod-pull_mysql_connector_test.yaml'
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
        timeout(time: 45, unit: 'MINUTES')
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
                dir("tiproxy") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', "master", REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
                dir("mysql-server") {
                    cache(path: "./", filter: '**/*', key: "git/xhebox/mysql-server/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/xhebox/mysql-server/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: true,
                                scm: [
                                    $class: 'GitSCM',
                                    branches: [[name: "8.0"]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 5, depth: 1, shallow: true],
                                    ], 
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        credentialsId: GIT_CREDENTIALS_ID,
                                        refspec: "+refs/heads/8.0:refs/remotes/origin/8.0",
                                        url: "https://github.com/xhebox/mysql-server.git",
                                    ]]
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb-test') {
                        sh "touch ws-${BUILD_TAG}"
                        sh label: 'prepare thirdparty binary', script: """
                        chmod +x download_binary.sh
                        ./download_binary.sh --tidb=master
                        ls -alh bin/
                        ./bin/tidb-server -V
                        """
                }
            }
        }
        stage('MySQL Connector Tests') {
            steps {
                dir('tidb-test') {
                    sh label: "run test", script: """
                        #!/usr/bin/env bash
                        ./bin/tidb-server &
                        TIDB_PID=\$!
                        ./mysql_client_test/test.sh -l 127.0.0.1 -p 4000 -t \$PWD/../tiproxy -m \$PWD/../mysql-server -u root
                        kill \$TIDB_PID || true
                    """
                }
            }
        }
    }
}
