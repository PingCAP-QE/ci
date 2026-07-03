// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-7.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-7.5/pod-pull_cdc_integration_mysql_test.yaml'
final POD_TEMPLATE_FILE_BUILD = 'pipelines/pingcap/tiflow/release-7.5/pod-pull_cdc_integration_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final HOTFIX_INFO = component.extractHotfixInfo(REFS.base_ref)
final OCI_TAG_PD = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIDB = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, REFS.base_ref)
final OCI_TAG_TIFLASH = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'v7.5.7'
final OCI_TAG_MINIO = 'RELEASE.2025-07-23T15-54-02Z'
final OCI_TAG_ETCD = 'v3.5.15'
final OCI_TAG_YCSB = 'v1.0.3'
final OCI_TAG_SCHEMA_REGISTRY = 'latest'
final WORKSPACE_STASH_NAME = 'tiflow-cdc-workspace'

pipeline {
    agent none
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE_BUILD, REFS)
                    retries 2
                    workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    defaultContainer 'golang'
                }
            }
            stages {
                stage('Checkout') {
                    options { timeout(time: 10, unit: 'MINUTES') }
                    steps {
                        dir(REFS.repo) {
                            script {
                                prow.checkoutRefsWithCacheLock(REFS)
                            }
                        }
                    }
                }
                stage("Prepare") {
                    options { timeout(time: 20, unit: 'MINUTES') }
                    steps {
                        dir("third_party_download") {
                            retry(2) {
                                sh label: "prepare third_party dir", script: "mkdir -p bin"
                                container("utils") {
                                    dir("bin") {
                                        sh label: "download third_party from OCI", script: """
                                            script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                            \$script \
                                                --tidb=${OCI_TAG_TIDB} \
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
                        dir(REFS.repo) {
                            cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'cdc-integration-test')) {
                                // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                                // only build binarys if not exist, use the cached binarys if exist
                                sh label: "prepare", script: """
                                    mkdir -p ./bin
                                    ls -alh ./bin
                                    [ -f ./bin/cdc ] || make cdc
                                    [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                                    [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                                    [ -f ./bin/cdc.test ] || make integration_test_build
                                    ls -alh ./bin
                                    ./bin/cdc version
                                """
                            }
                            sh label: "prepare workspace", script: """
                                cp -r ../third_party_download/bin/* ./bin/
                                ln -sf /usr/bin/jq ./bin/jq
                                make check_third_party_binary
                                ls -alh ./bin
                                ./bin/tidb-server -V
                                ./bin/pd-server -V
                                ./bin/tikv-server -V
                                ./bin/tiflash --version
                            """
                            stash includes: '**/*', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13',
                            'G14', 'G15', 'G16', 'G17', 'G18', 'G19', 'G20', 'G21'
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
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_group: env.TEST_GROUP]) }
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
                                unstash name: WORKSPACE_STASH_NAME
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
                                    ls /tmp/tidb_cdc_test/
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/tidb_cdc_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [test_group: env.TEST_GROUP]) } }
                        }
                    }
                }
            }
        }
    }
}
