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
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiproxy-mysql-test") {
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
                stash includes: '**/*', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
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
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [part: env.PART]) }
                }
                stages {
                    stage("Test") {
                        steps {
                            unstash name: WORKSPACE_STASH_NAME
                            dir('tidb-test') {
                                sh label: "PART ${PART}", script: """
                                    #!/usr/bin/env bash
                                    make deploy-mysqltest ARGS="-b -x y -s tikv -p ${PART}"
                                """
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [part: env.PART]) } }
                        }
                    }
                }
            }
        }
    }
}
