// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-9.0-beta/pod-pull_mysql_client_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
prow.setPRDescription(REFS)

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            retries 2
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID)
                    }
                }
                dir("tidb-test") {
                    retry(2) {
                        script {
                            component.checkout('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir(REFS.repo) {
                    sh label: 'tidb-server', script: '[ -f bin/tidb-server ] || make'
                }
                dir('tidb-test') {
                    sh label: 'prepare', script: """
                        touch ws-${BUILD_TAG}
                        mkdir -p bin/
                        cp ../tidb/bin/tidb-server bin/ && chmod +x bin/tidb-server
                        ls -alh bin/
                        ./bin/tidb-server -V
                    """
                }
            }
        }
        stage('MySQL Connector Tests') {
            steps {
                container('mysql-client-test') {
                    dir('tidb-test') {
                        sh label: "run test", script: """#!/usr/bin/env bash
                            make mysql_client_test
                        """
                    }
                }
            }
        }
    }
}
