// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/ticdc'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-test.yaml"
final POD_TEMPLATE_FILE_BUILD = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod-build.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE_BUILD
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 100, unit: 'MINUTES')
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
                    script {
                        prow.setPRDescription(REFS)
                    }
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
            }
        }
        stage("prepare") {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    script {
                        def tidbBranch = component.computeBranchFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
                        def pdBranch = component.computeBranchFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
                        def tikvBranch = component.computeBranchFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
                        def tiflashBranch = component.computeBranchFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
                        retry(2) {
                            sh label: "download third_party", script: """
                                export TIDB_BRANCH=${tidbBranch}
                                export PD_BRANCH=${pdBranch}
                                export TIKV_BRANCH=${tikvBranch}
                                export TIFLASH_BRANCH=${tiflashBranch}
                                cd ../ticdc && ./tests/scripts/download-integration-test-binaries.sh ${REFS.base_ref} && ls -alh ./bin
                                make check_third_party_binary
                                cd - && mkdir -p bin && mv ../ticdc/bin/* ./bin/
                                ls -alh ./bin
                                ./bin/tidb-server -V
                                ./bin/pd-server -V
                                ./bin/tikv-server -V
                                ./bin/tiflash --version
                            """
                        }
                    }
                }
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-test')) {
                        // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc.test ] || make integration_test_build
                            ls -alh ./bin
                            ./bin/cdc version
                        """
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                        """
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08',
                            'G09', 'G10', 'G11', 'G12', 'G13', 'G14', 'G15'
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 80, unit: 'MINUTES') }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                                    sh label: "${TEST_GROUP}", script: """
                                        ./tests/integration_tests/run_heavy_it_in_ci.sh mysql ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tidb_cdc_test/
                                    tar --warning=no-file-changed  -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                        }
                    }
                }
            }
        }
    }
}
