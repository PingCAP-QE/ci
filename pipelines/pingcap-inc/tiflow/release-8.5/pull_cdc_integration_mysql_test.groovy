// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap-inc/tiflow/release-8.5/pod-pull_cdc_integration_mysql_test.yaml'
final POD_TEMPLATE_FILE_BUILD = 'pipelines/pingcap-inc/tiflow/release-8.5/pod-pull_cdc_integration_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final HOTFIX_INFO = component.extractHotfixInfo(REFS.base_ref)
final OCI_TAG_PD = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIDB = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, REFS.base_ref)
final OCI_TAG_TIFLASH = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'master'
final OCI_TAG_MINIO = 'RELEASE.2025-07-23T15-54-02Z'
final OCI_TAG_ETCD = 'v3.5.15'
final OCI_TAG_YCSB = 'v1.0.3'
final OCI_TAG_SCHEMA_REGISTRY = 'latest'

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE_BUILD, REFS)
                    defaultContainer 'golang'
                }
            }
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID, withSubmodule = true)
                    }
                    script {
                        cdc.prepareIntegrationTestCommonBinariesWithCacheLock(REFS, 'binary')
                        cdc.prepareIntegrationTestKafkaConsumerBinariesWithCacheLock(REFS, 'binary')
                        cdc.prepareIntegrationTestStorageConsumerBinariesWithCacheLock(REFS, 'binary')
                    }
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        export script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        chmod +x \$script
                                        OCI_ARTIFACT_HOST=us-docker.pkg.dev/pingcap-testing-account/internal \$script --tidb=${OCI_TAG_TIDB}
                                        \$script \
                                            --pd=${OCI_TAG_PD} \
                                            --pd-ctl=${OCI_TAG_PD} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --tiflash=${OCI_TAG_TIFLASH} \
                                            --sync-diff-inspector=${OCI_TAG_SYNC_DIFF_INSPECTOR} \
                                            --minio=${OCI_TAG_MINIO} \
                                            --etcdctl=${OCI_TAG_ETCD} \
                                            --ycsb=${OCI_TAG_YCSB} \
                                            --schema-registry=${OCI_TAG_SCHEMA_REGISTRY}
                                    """
                                }
                            }
                        }
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                        sh label: "prepare", script: "ls -alh ./bin"
                    }
                }
            }
        }

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13',
                            'G14', 'G15', 'G16', 'G17', 'G18', 'G19', 'G20', 'G21'
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment {
                            TICDC_CODECOV_TOKEN = credentials('codecov-token-tiflow')
                            TICDC_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')
                        }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-cdc") {
                                    sh """
                                        ln -sf /usr/bin/jq ./bin/jq
                                        make check_third_party_binary
                                        ls -alh ./bin
                                        ./bin/tidb-server -V
                                        ./bin/pd-server -V
                                        ./bin/tikv-server -V
                                        ./bin/tiflash --version
                                    """
                                }
                                sh label: "${TEST_GROUP}", script: """
                                    rm -rf /tmp/tidb_cdc_test && mkdir -p /tmp/tidb_cdc_test
                                    chmod +x ./tests/integration_tests/run_group.sh
                                    ./tests/integration_tests/run_group.sh mysql ${TEST_GROUP}
                                """
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/tidb_cdc_test/ || true
                                    log_files=\$(find /tmp/tidb_cdc_test/ -type f -name "*.log" 2>/dev/null || true)
                                    if [ -n "\${log_files}" ]; then
                                        tar --warning=no-file-changed -cvzf log-${TEST_GROUP}.tar.gz \${log_files}
                                    else
                                        tar -czf log-${TEST_GROUP}.tar.gz --files-from /dev/null
                                    fi
                                    ls -alh log-${TEST_GROUP}.tar.gz
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
