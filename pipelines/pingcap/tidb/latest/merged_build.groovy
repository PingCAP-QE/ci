// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-merged_build.yaml'
final REFS = prow.getJobRefs(params.PROW_DECK_URL, params.PROW_JOB_ID)

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
                            cache(path: "./", filter: '**/*', key: "git/${REFS.org}/${REFS.repo}/rev-${REFS.base_sha}", restoreKeys: ["git/${REFS.org}/${REFS.repo}/rev-"]) {
                                retry(2) {
                                    script {
                                        prow.checkoutBase(params.PROW_DECK_URL, params.PROW_JOB_ID)
                                    }
                                }
                            }
                        }
                    }
                }
                stage("enterprise-plugin") {
                    steps {
                        dir("enterprise-plugin") {
                            cache(path: "./", filter: '**/*', key: "git/pingcap/enterprise-plugin/rev-${REFS.base_sha}", restoreKeys: ['git/pingcap/enterprise-plugin/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkout('git@github.com:pingcap/enterprise-plugin.git', 'plugin', REFS.base_ref, "", GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server and plugin"){
            stage("Build tidb-server") {
                stages {
                    stage("Build"){
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
                            export PD_BRANCH=${REFS.base_ref}
                            export TIKV_BRANCH=${REFS.base_ref}
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
}
