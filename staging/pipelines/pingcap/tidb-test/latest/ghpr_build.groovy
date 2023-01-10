// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb-test'
final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap/tidb-test/latest/pod-ghpr_build.yaml'

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
        timeout(time: 30, unit: 'MINUTES')
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
            stage('tidb-test') {
                steps {
                    dir('tidb-test') {
                        cache(path: "./", filter: '**/*', key: "git/pingcap/tidb-test/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb/rev-']) {
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
        }
        stage("Build"){
            dir("tidb-test") {

            }
        }
    }
}