// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-qe/tidb-test'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final WORKSPACE_STASH_NAME = 'tidb-test-workspace'
final TIDB_BIN_STASH_NAME = 'tidb-bin'

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
                    cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/dependencies") {
                        sh label: 'tidb-server', script: 'make'
                        container("utils") {
                            dir("bin") {
                                retry(3) {
                                    sh label: 'download tidb components', script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV}
                                    """
                                }
                            }
                        }
                        sh label: "check binary", script: """
                                pwd && ls -alh
                                ls bin/tidb-server && ./bin/tidb-server -V
                                ls bin/pd-server && ./bin/pd-server -V
                                ls bin/tikv-server && ./bin/tikv-server -V
                            """
                    }
                }
                dir('tidb-test') {
                    cache(path: "./mysql_test", includes: '**/*', key: "ws/${BUILD_TAG}/mysql-test") {
                        sh "touch ws-${BUILD_TAG}"
                    }
                    stash includes: '**/*', excludes: '**/.git', name: WORKSPACE_STASH_NAME
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
                            dir('tidb-test') {
                                unstash name: WORKSPACE_STASH_NAME
                                dir('mysql_test') {
                                    sh label: "PART ${PART}", script: """
                                        #!/usr/bin/env bash
                                        ls -alh
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                        export TIDB_SERVER_PATH="${WORKSPACE}/tidb-test/mysql_test/bin/tidb-server"
                                        export TIKV_PATH="127.0.0.1:2379"
                                        export TIDB_TEST_STORE_NAME="tikv"
                                        ./test.sh 1 ${PART}
                                    """
                                }
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
