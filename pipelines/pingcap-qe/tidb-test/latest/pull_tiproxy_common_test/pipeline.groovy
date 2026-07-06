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
                dir("tidb-test") {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
                dir('tidb-test') {
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        retry(2) {
                            sh "touch ws-${BUILD_TAG}"
                            container("utils") {
                                dir('bin') {
                                    sh label: 'download thirdparty binary', script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --tidb=master --pd=master --tikv=master --tiproxy=main
                                    """
                                }
                            }
                            sh label: 'prepare tiproxy binary', script: """
                            ls -alh bin/
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                            ./bin/tiproxy --version
                            """
                        }
                    }
                    stash includes: '**/*', name: WORKSPACE_STASH_NAME
                }
            }
        }
        stage('MySQL Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_CMDS'
                        values 'make deploy-analyzetest ARGS="-x"', 'make deploy-randgentest ARGS="-x -c y"',
                            'make deploy-gosqltest ARGS="-x"', 'make deploy-gormtest ARGS="-x"',
                            'make deploy-beegoormtest ARGS="-x"', 'make deploy-upperdbormtest ARGS="-x"',
                            'make deploy-xormtest ARGS="-x"'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
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
                            dir('tidb-test') {
                                unstash name: WORKSPACE_STASH_NAME
                                sh label: "test_cmds=${TEST_CMDS} ", script: """
                                    #!/usr/bin/env bash
                                    ${TEST_CMDS}
                                """
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
