// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final COMMIT_CONTEXT = 'staging/build'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_build.yaml'

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
            parallel {   
                stage('tidb') {
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
                stage("enterprise-plugin") {
                    steps {
                        dir("enterprise-plugin") {
                            cache(path: "./", filter: '**/*', key: "git/pingcap/enterprise-plugin/rev-${GIT_MERGE_COMMIT}", restoreKeys: ['git/pingcap/enterprise-plugin/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkout('git@github.com:pingcap/enterprise-plugin.git', 'plugin', GIT_BASE_BRANCH, "", GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server") {
            steps {
                dir("tidb") {                                     
                    sh "make bazel_build"
                }
            }
            post {       
                // TODO: statics and report logic should not put in pipelines.
                // Instead should only send a cloud event to a external service.
                always {
                    dir("tidb") {
                        archiveArtifacts(
                            artifacts: 'importer.log,tidb-server-check.log',
                            allowEmptyArchive: true,
                        )
                    }            
                }
            }
        }
        stage("Plugin Test") {
            steps {
                timeout(time: 20, unit: 'MINUTES') {
                    timeout(time: 5, unit: 'MINUTES') {
                        sh label: 'build pluginpkg tool', script: 'cd tidb/cmd/pluginpkg && go build'
                    }
                    dir('enterprise-plugin/whitelist') {
                        sh label: 'build plugin whitelist', script: '''
                        GO111MODULE=on go mod tidy
                        ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                        '''
                    }
                    dir("enterprise-plugin") {
                        sh label: 'audit plugin test', script: """
                        go version
                        cd test/
                        export PD_BRANCH=${GIT_BASE_BRANCH}
                        export TIKV_BRANCH=${GIT_BASE_BRANCH}
                        export TIDB_REPO_PATH=${WORKSPACE}/tidb
                        export PLUGIN_REPO_PATH=${WORKSPACE}/enterprise-plugin
                        ./test.sh
                        """
                    }
                }
            }
        }
    }
}
