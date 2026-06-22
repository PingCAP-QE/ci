// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap-qe/tidb-test'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "cloud-engine-nextgen")
final WORKSPACE_STASH_NAME = 'tidb-test-workspace'


prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        NEXT_GEN = '1'
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
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
            options { timeout(time: 5, unit: 'MINUTES') }
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
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID)
                    }
                }
                dir('tidb') {
                    sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                }
                dir(REFS.repo) {
                    // Prepare component binaries.
                    dir('bin') {
                        container("utils") {
                            withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                                sh label: "prepare docker auth", script: '''
                                    mkdir -p ~/.docker
                                    cp ${DOCKER_CONFIG_JSON} ~/.docker/config.json
                                '''
                            }
                            sh """
                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --tikv-worker=${OCI_TAG_TIKV}
                            """
                            sh "cp ${WORKSPACE}/tidb/bin/* ./"
                        }
                    }
                }
                stash includes: '**/*', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'PART'
                        values '1', '2', '3', '4'
                    }
                    axis {
                        name 'STORE'
                        values 'unistore' //, 'tikv'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'golang'
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [part: env.PART, store: env.STORE]) }
                }
                stages {
                    stage('Test') {
                        steps {
                            dir(REFS.repo) {
                                unstash name: WORKSPACE_STASH_NAME
                                sh 'ls mysql_test && chmod +x bin/{tidb-server,pd-server,tikv-server,tikv-worker}'
                                sh label: "store=${STORE} part=${PART}", script: """#!/usr/bin/env bash
                                    if [ "$STORE" == "tikv" ]; then
                                        echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
                                        bash ${WORKSPACE}/scripts/PingCAP-QE/tidb-test/start_tikv.sh
                                        export TIKV_PATH="127.0.0.1:2379"
                                    fi
                                    pushd mysql_test
                                        TIDB_SERVER_PATH=../bin/tidb-server TIDB_TEST_STORE_NAME="${STORE}" ./test.sh 1 ${PART}
                                    popd
                                """
                            }
                        }
                        post{
                            always {
                                junit(testResults: "**/result.xml")
                            }
                            unsuccessful {
                                archiveArtifacts(artifacts: 'tidb-test/mysql_test/mysql-test.out*', allowEmptyArchive: true)
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [part: env.PART, store: env.STORE]) } }
                        }
                    }
                }
            }
        }
    }
}
