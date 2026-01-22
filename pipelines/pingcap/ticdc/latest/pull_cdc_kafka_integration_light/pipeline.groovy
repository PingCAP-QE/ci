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
final CACHE_KEY_CONSUMER_BINARY = prow.getCacheKey('binary', REFS)
final OCI_TAG_PD = component.computeBranchFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIDB = component.computeBranchFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeBranchFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeBranchFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'master'
final OCI_TAG_MINIO = 'RELEASE.2025-07-23T15-54-02Z'
final OCI_TAG_ETCD = 'v3.5.15'
final OCI_TAG_YCSB = 'v1.0.3'
final OCI_TAG_SCHEMA_REGISTRY = 'latest'

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        // internal mirror is 'hub-zot.pingcap.net/mirrors/hub'
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
                    yamlFile POD_TEMPLATE_FILE_BUILD
                    defaultContainer 'golang'
                }
            }
            steps {
                dir(REFS.repo) {
                    // Checkout
                    script {
                        prow.checkoutRefsWithCacheLock(REFS)
                    }
                    // Build common binaries
                    script {
                        cdc.prepareCommonIntegrationTestBinariesWithCacheLock(REFS, 'binary')
                    }
                    // Build job-specific binaries
                    lock(CACHE_KEY_CONSUMER_BINARY) {
                        cache(path: "./bin", includes: '**/*', key: CACHE_KEY_CONSUMER_BINARY) {
                            // build kafka_consumer, storage_consumer for integration test
                            // only build binarys if not exist, use the cached binarys if exist
                            sh label: "prepare", script: """
                                [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                                [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                                ls -alh ./bin
                            """
                        }
                    }
                    // Download other binaries
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        export script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        chmod +x \$script
                                        \$script \
                                            --pd=${OCI_TAG_PD} \
                                            --pd-ctl=${OCI_TAG_PD} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --tidb=${OCI_TAG_TIDB} \
                                            --tiflash=${OCI_TAG_TIFLASH} \
                                            --sync-diff-inspector=${OCI_TAG_SYNC_DIFF_INSPECTOR} \
                                            --minio=${OCI_TAG_MINIO} \
                                            --etcdctl=${OCI_TAG_ETCD} \
                                            --ycsb=${OCI_TAG_YCSB} \
                                            --schema-registry=${OCI_TAG_SCHEMA_REGISTRY}

                                        ls -d tiflash
                                        mv tiflash tiflash-dir
                                        mv tiflash-dir/* .
                                        rm -rf tiflash-dir
                                    """
                                }
                            }
                        }
                    }
                    // Cache for downstream test stages.
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
                        sh label: "prepare", script: """
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08', 'G09', 'G10', 'G11', 'G12', 'G13', 'G14', 'G15'
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
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/ticdc") {
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
                                container("kafka") {
                                    timeout(time: 6, unit: 'MINUTES') {
                                        sh label: "Waiting for kafka ready", script: """
                                            echo "Waiting for zookeeper to be ready..."
                                            while ! nc -z localhost 2181; do sleep 10; done
                                            echo "Waiting for kafka to be ready..."
                                            while ! nc -z localhost 9092; do sleep 10; done
                                            echo "Waiting for kafka-broker to be ready..."
                                            while ! echo dump | nc localhost 2181 | grep brokers | awk '{\$1=\$1;print}' | grep -F -w "/brokers/ids/1"; do sleep 10; done
                                        """
                                    }
                                }
                                sh label: "${TEST_GROUP}", script: """
                                    ./tests/integration_tests/run_light_it_in_ci.sh kafka ${TEST_GROUP}
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
