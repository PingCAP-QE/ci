// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'PingCAP-QE/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiproxy/latest/pod-pull_mysql_test.yaml'
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
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tiproxy") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutV2('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', "master", REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tiproxy') {
                    sh label: 'tiproxy', script: 'ls bin/tiproxy || make'
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch ws-${BUILD_TAG}"
                        sh label: 'prepare thirdparty binary', script: """
                        chmod +x download_binary.sh
                        ./download_binary.sh --tidb=master --pd=master --tikv=master
                        cp ../tiproxy/bin/tiproxy ./bin/
                        ls -alh bin/
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                        ./bin/tiproxy --version
                        """
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
                            dir('tidb-test') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh label: "PART ${PART}", script: """
                                        #!/usr/bin/env bash
                                        make deploy-mysqltest ARGS="-b -x y -s tikv -p ${PART}"
                                    """
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
