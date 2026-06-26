// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-qe/tidb-test'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs
final WORKSPACE_STASH_NAME = 'tidb-test-workspace'

pipeline {
    agent none
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                    retries 2
                    workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    defaultContainer 'golang'
                }
            }
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiproxy") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tiproxy/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tiproxy/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tiproxy.git', 'tiproxy', "main", "", "")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
                dir('tiproxy') {
                    sh label: 'tiproxy', script: '[ -f bin/tiproxy ] || make'
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        retry(2) {
                            sh "touch ws-${BUILD_TAG}"
                            container("utils") {
                                dir('bin') {
                                    sh label: 'download thirdparty binary', script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --tidb=master --pd=master --tikv=master
                                    """
                                }
                            }
                            sh label: 'prepare tiproxy binary', script: """
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
                stash includes: '**/*', excludes: '**/.git', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
            }
        }
        stage('ORM Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMDS'
                        values 'make deploy-activerecordtest ARGS="-x"'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'ruby'
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_cmds: env.TEST_CMDS]) }
                }
                stages {
                    stage("Test") {
                        steps {
                            unstash name: WORKSPACE_STASH_NAME
                            dir('tidb-test') {
                                container("ruby") {
                                    sh label: "prepare ruby test deps", script: """
                                        #!/usr/bin/env bash
                                        set -euxo pipefail
                                        if ! command -v mysql >/dev/null 2>&1 || ! dpkg-query -W default-libmysqlclient-dev >/dev/null 2>&1 || ! dpkg-query -W libsqlite3-dev >/dev/null 2>&1; then
                                            apt-get update
                                            DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \\
                                                default-mysql-client \\
                                                build-essential \\
                                                default-libmysqlclient-dev \\
                                                libsqlite3-dev \\
                                                pkg-config
                                            rm -rf /var/lib/apt/lists/*
                                        fi
                                        if ! gem list -i bundler -v 2.3.17 >/dev/null 2>&1; then
                                            gem install bundler -v 2.3.17
                                        fi
                                        command -v mysql
                                        bundle -v
                                    """
                                    sh label: "test_cmds=${TEST_CMDS} ", script: """
                                        #!/usr/bin/env bash
                                        ${TEST_CMDS}
                                    """
                                }
                            }
                        }
                        post{
                            failure {
                                script {
                                    println "Test failed, archive the log"
                                }
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [test_cmds: env.TEST_CMDS]) } }
                        }
                    }
                }
            }
        }
    }
}
