// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb-engine-ext'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb-engine-ext/release-6.1/pod-ghpr_unit_test.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'rust'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
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
            steps {
                dir('tidb-engine-ext') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-engine-ext/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb-engine-ext/rev-']) {
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
                                        [$class: 'CloneOption', timeout: 5],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git"
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage('Test') {
            steps {
                dir('tidb-engine-ext') {
                    sh label: "debug git root path", script: """
                        pwd 
                        git rev-parse --show-toplevel
                        ls -alh ../
                    """
                    sh """
                    set -euox pipefail
                    make ci_fmt_check
                    make ci_test
                    """ 
                }
            }
            post {
                unsuccessful {
                    dir('tidb-engine-ext') {
                        archiveArtifacts(artifacts: '**/*.log', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
