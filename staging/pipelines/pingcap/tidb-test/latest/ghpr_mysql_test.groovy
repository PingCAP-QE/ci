// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb-test/latest/pod-ghpr_mysql_test.yaml'

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
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
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
                        // FIXME: https://github.com/pingcap/tidb-test/issues/1987
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || go build -race -o bin/tidb-server ./tidb-server'
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
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "binary/pingcap/tidb-test/rev-${ghprbActualCommit}") {
                                    sh 'ls mysql_test' // if cache missed, fail it(should not miss).
                                    dir('mysql_test') {
                                        sh label: "part ${PART},CACHE_ENABLED ${CACHE_ENABLED}", script: """
                                        export TIDB_SERVER_PATH=${WORKSPACE}/tidb/bin/tidb-server
                                        export CACHE_ENABLED=${CACHE_ENABLED}
                                        export TIDB_TEST_STORE_NAME="unistore"
                                        ./test.sh -backlist=1 -part=${PART}
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
