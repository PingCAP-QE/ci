// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/ticdc'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs
final BINARY_CACHE_KEY = prow.getCacheKey('ng-binary', REFS)

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIFLASH = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "dedicated-next-gen")
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'master'
final OCI_TAG_MINIO = 'RELEASE.2025-07-23T15-54-02Z'
final OCI_TAG_ETCD = 'v3.5.15'
final OCI_TAG_YCSB = 'v1.0.3'
final OCI_TAG_SCHEMA_REGISTRY = 'latest'

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        // internal mirror is 'hub-zot.pingcap.net/mirrors/tidbx'
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
        NEXT_GEN = 1
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
                    yamlFile POD_TEMPLATE_FILE
                    defaultContainer 'golang'
                }
            }
            steps {
                dir(REFS.repo) {
                    // Checkout
                    script {
                        prow.checkoutRefsWithCacheLock(REFS)
                    }
                    // Build binaries
                    lock(BINARY_CACHE_KEY) {
                        cache(path: "./bin", includes: '**/*', key: binaryCacheKey) {
                            // build cdc, kafka_consumer, storage_consumer, cdc.test for integration test
                            // only build binarys if not exist, use the cached binarys if exist
                            sh label: "prepare", script: """
                                [ -f ./bin/cdc ] || make cdc
                                [ -f ./bin/cdc_kafka_consumer ] || make kafka_consumer
                                [ -f ./bin/cdc_storage_consumer ] || make storage_consumer
                                [ -f ./bin/cdc.test ] || make integration_test_build
                                ls -alh ./bin
                                ./bin/cdc version
                            """
                        }
                    }
                    // Download other binaries
                    container("utils") {
                        withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                            sh label: "prepare docker auth", script: '''
                                mkdir -p ~/.docker
                                cp ${DOCKER_CONFIG_JSON} ~/.docker/config.json
                            '''
                        }
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
                                            --tikv-worker=${OCI_TAG_TIKV} \
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
                    // Cache for downstream test stages
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
                                sh label: "${TEST_GROUP}", script: """
                                    ./tests/integration_tests/run_heavy_it_in_ci.sh storage ${TEST_GROUP}
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
