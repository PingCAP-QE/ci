// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = 'jenkins-tidb'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "dedicated-next-gen")

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        NEXT_GEN = '1'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
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
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: "git/PingCAP-QE/tidb-test/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/PingCAP-QE/tidb-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutSupportBatch('git@github.com:PingCAP-QE/tidb-test.git', 'tidb-test', REFS.base_ref, REFS.pulls[0].title, REFS, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: "ng-binary/pingcap/tidb/tidb-server/rev-${REFS.base_sha}-${REFS.pulls[0].sha}") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                }
                dir('tidb-test') {
                    sh "git branch && git status"
                    // Prepare component binaries.
                    dir('bin') {
                        container("utils") {
                            sh """
                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --tikv-worker=${OCI_TAG_TIKV}
                            """
                            sh "cp ${WORKSPACE}/${REFS.repo}/bin/* ./"
                        }
                    }
                    // Cache for next test stages.
                    cache(path: './', includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                        sh label: 'cache tidb-test', script: 'touch ws-${BUILD_TAG}'
                    }
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
                    axis {
                        name 'STORE'
                        // values 'unistore', 'tikv'
                        values 'unistore'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage('Test') {
                        options { timeout(time: 25, unit: 'MINUTES') }
                        steps {
                            dir('tidb-test') {
                                // restore the cache saved by previous stage.
                                cache(path: './', includes: '**/*', key: "ws/${BUILD_TAG}/tidb-test") {
                                    // if cache missed, it will fail(should not miss).
                                    sh 'ls mysql_test && chmod +x bin/{tidb-server,pd-server,tikv-server,tikv-worker}'
                                }

                                // run the test.
                                // TODO: use a script for next-gen, consider merging the script.
                                sh label: "store=${STORE} part=${PART}", script: """#!/usr/bin/env bash
                                    set -euo pipefail

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
                        }
                    }
                }
            }
        }
    }
}
