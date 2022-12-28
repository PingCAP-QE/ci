// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.4/pod-ghpr_mysql_test.yaml'
final REFS = prow.getPrRefs(params.PROW_DECK_URL, params.PROW_JOB_ID)

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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                prow.checkoutPr(params.PROW_DECK_URL, params.PROW_JOB_ID)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb-test.git', 'tidb-test', REFS.base_ref, '', GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.pulls[0].sha}") {
                        // FIXME: https://github.com/pingcap/tidb-test/issues/1987
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || go build -race -o bin/tidb-server ./tidb-server'
                    }
                }
                dir('tidb-test') {
                    sh "git branch && git status"
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
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
                                cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${REFS.pulls[0].sha}") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server'
                                }
                            }
                            dir('tidb-test') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    sh 'ls mysql_test' // if cache missed, fail it(should not miss).
                                    dir('mysql_test') {
                                        sh label: "part ${PART}", script: 'TIDB_SERVER_PATH=${WORKSPACE}/tidb/bin/tidb-server ./test.sh -backlist=1 -part=${PART}'
                                    }
                                }
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                            failure {
                                archiveArtifacts(artifacts: 'mysql-test.out*', allowEmptyArchive: true)
                            }
                        }
                    }
                }
            }        
        }
    }
    post {
        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {
                sh "bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json || true"
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}