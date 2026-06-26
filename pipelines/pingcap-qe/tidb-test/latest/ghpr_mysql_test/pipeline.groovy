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
final TIDB_BIN_STASH_NAME = 'tidb-bin'

pipeline {
    agent none
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
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, trunkBranch=REFS.base_ref, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
                dir('tidb') {
                    cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-server") {
                        sh label: 'tidb-server', script: 'make'
                    }
                    stash includes: 'bin/**', name: TIDB_BIN_STASH_NAME
                }
                dir('tidb-test') {
                    stash includes: '**/*', name: WORKSPACE_STASH_NAME
                }
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
                            dir('tidb') {
                                unstash name: TIDB_BIN_STASH_NAME
                            }
                            dir('tidb-test') {
                                unstash name: WORKSPACE_STASH_NAME
                                dir('mysql_test') {
                                    sh label: "part ${PART}", script: """
                                    export TIDB_SERVER_PATH=${WORKSPACE}/tidb/bin/tidb-server
                                    export TIDB_TEST_STORE_NAME="unistore"
                                    ./test.sh 1 ${PART}
                                    """
                                }
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                            unsuccessful {
                                archiveArtifacts artifacts: "tidb-test/mysql_test/mysql-test.out", fingerprint: true, allowEmptyArchive: true
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [part: env.PART]) } }
                        }
                    }
                }
            }
        }
    }
}
