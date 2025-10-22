// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/ticdc'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = 'pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

final tidbBranch = "master"
final pdBranch = "master"
final tikvBranch = "dedicated"
final tiflashBranch = "master"

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
        NEXT_GEN = 1
    }
    options {
        timeout(time: 80, unit: 'MINUTES')
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
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('ng-binary', REFS, 'cdc-storage-integration')) {
                        // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            ls -alh ./bin
                            [ -f ./bin/cdc ] || make cdc
                            [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                            [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                            [ -f ./bin/cdc.test ] || make integration_test_build
                            ls -alh ./bin
                            ./bin/cdc version
                        """
                    }
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download third_party", script: """
                                        export next_gen_artifact_script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                        chmod +x $next_gen_artifact_script
                                        ${next_gen_artifact_script} \
                                            --pd=${TARGET_BRANCH_PD}-next-gen \
                                            --tikv=${TARGET_BRANCH_TIKV}-next-gen \
                                            --tikv-worker=${TARGET_BRANCH_TIKV}-next-gen \
                                            --minio=RELEASE.2025-07-23T15-54-02Z

                                    """
                                }
                            }
                        }
                        dir(REFS.repo) {
                            script {
                                retry(2) {
                                    sh label: "download third_party", script: """
                                        ./tests/scripts/download-integration-test-binaries-next-gen.sh && ls -alh ./bin
                                    """
                                }
                            }
                        }
                        cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                            sh label: "prepare", script: """
                                ls -alh ./bin
                            """
                        }
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09',
                            'G10', 'G11', 'G12', 'G13', 'G14', 'G15'
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
                        options { timeout(time: 60, unit: 'MINUTES') }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                                    sh """
                                        make check_third_party_binary
                                        ls -alh ./bin
                                        ./bin/tidb-server -V
                                        ./bin/pd-server -V
                                        ./bin/tikv-server -V
                                        ./bin/tiflash --version
                                    """
                                }
                                sh label: "${TEST_GROUP}", script: """
                                    ./tests/integration_tests/run_light_it_in_ci.sh storage ${TEST_GROUP}
                                """
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
