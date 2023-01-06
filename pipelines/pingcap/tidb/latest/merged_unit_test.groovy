// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/unit-test'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_unit_test.yaml'

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
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: GIT_MERGE_COMMIT ]],
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
            }
        }
        stage('Tests') {
            options { timeout(time: 30, unit: 'MINUTES') }
            environment { TIDB_CODECOV_TOKEN = credentials('codecov-token-tidb') }
            steps {
                dir('tidb') {
                    sh './build/jenkins_unit_test.sh' 
                }
            }
            post {
                 success {
                    dir("tidb") {
                        sh label: "upload coverage to codecov", script: """
                        mv coverage.dat test_coverage/coverage.dat
                        wget -q -O codecov ${FILE_SERVER_URL}/download/cicd/tools/codecov-v0.3.2
                        chmod +x codecov
                        ./codecov --dir test_coverage/ --token ${TIDB_CODECOV_TOKEN} -B ${GIT_BASE_BRANCH} -C ${GIT_MERGE_COMMIT}
                        """
                    }
                }
                always {
                    dir('tidb') {
                        // archive test report to Jenkins.
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)

                        // upload coverage report to file server
                        retry(3) {
                            sh label: "upload coverage report to ${FILE_SERVER_URL}", script: """
                                filepath="tipipeline/test/report/\${JOB_NAME}/\${BUILD_NUMBER}/\${GIT_MERGE_COMMIT}/report.xml"
                                curl -f -F \${filepath}=@test_coverage/bazel.xml \${FILE_SERVER_URL}/upload
                                echo "coverage download link: \${FILE_SERVER_URL}/download/\${filepath}"
                                """
                        }
                    }
                }
            }      
        }
    }
}
