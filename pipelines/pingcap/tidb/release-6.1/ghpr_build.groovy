// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.1/pod-ghpr_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            parallel {
                stage('tidb') {
                    steps {
                        dir('tidb') {
                            cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                                retry(2) {
                                    script {
                                        prow.checkoutRefs(REFS)
                                    }
                                }
                            }
                        }
                    }
                }
                stage("enterprise-plugin") {
                    steps {
                        dir("enterprise-plugin") {
                            cache(path: "./", includes: '**/*', key: "git/pingcap-inc/enterprise-plugin/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap-inc/enterprise-plugin/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkout('git@github.com:pingcap-inc/enterprise-plugin.git', 'plugin', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server and plugin"){
            parallel {
                stage("Build tidb-server") {
                    stages {
                        stage("Build"){
                            steps {
                                dir("tidb") {
                                    sh """
                                    make importer
                                    WITH_CHECK=1 make TARGET=bin/tidb-server-check
                                    make
                                    """
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
                        stage("Upload") {
                            options {
                                timeout(time: 5, unit: 'MINUTES')
                            }
                            steps {
                                dir("tidb") {
                                    sh label: "create tidb-server tarball", script: """
                                        rm -rf .git
                                        tar czvf tidb-server.tar.gz ./*
                                        echo "pr/${REFS.pulls[0].sha}" > sha1
                                        echo "done" > done
                                        """
                                    sh label: 'upload to tidb dir', script: """
                                        filepath="builds/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].sha}/centos7/tidb-server.tar.gz"
                                        donepath="builds/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].sha}/centos7/done"
                                        refspath="refs/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].number}/sha1"
                                        """
                                    sh label: 'upload to tidb-checker dir', script: """
                                        filepath="builds/pingcap/tidb-check/pr/${REFS.pulls[0].sha}/centos7/tidb-server.tar.gz"
                                        donepath="builds/pingcap/tidb-check/pr/${REFS.pulls[0].sha}/centos7/done"
                                        """
                                }
                            }
                        }
                    }
                }
                stage("Build plugins") {
                    steps {
                        timeout(time: 15, unit: 'MINUTES') {
                            sh label: 'build pluginpkg tool', script: 'cd tidb/cmd/pluginpkg && go build'
                        }
                        dir('enterprise-plugin/whitelist') {
                            sh label: 'build plugin whitelist', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                        dir('enterprise-plugin/audit') {
                            sh label: 'build plugin: audit', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                    }
                }
            }
        }
        stage("Test plugin") {
            steps {
                sh label: 'build tidb-server', script: 'make server -C tidb'
                sh label: 'Test plugins', script: '''
                  rm -rf /tmp/tidb
                  rm -rf plugin-so
                  mkdir -p plugin-so

                  cp enterprise-plugin/audit/audit-1.so ./plugin-so/
                  cp enterprise-plugin/whitelist/whitelist-1.so ./plugin-so/
                  ./tidb/bin/tidb-server -plugin-dir=./plugin-so -plugin-load=audit-1,whitelist-1 > /tmp/loading-plugin.log 2>&1 &

                  sleep 30
                  ps aux | grep tidb-server
                  cat /tmp/loading-plugin.log
                  killall -9 -r tidb-server
                '''
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
