// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-8.1 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_br_integration_test.yaml'
final OCI_TAG_YCSB = 'v1.0.3'
final OCI_TAG_FAKE_GCS_SERVER = 'v1.54.0'
final OCI_TAG_KES = 'v0.14.0'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
                container(name: 'utils') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    retry(2) {
                        script {
                            component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', "${params.RELEASE_BRANCH}", '', trunkBranch="${params.RELEASE_BRANCH}", timeout=5, credentialsId="")
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: "check all tests added to group", script: """#!/usr/bin/env bash
                        chmod +x br/tests/*.sh
                        ./br/tests/run_group_br_tests.sh others
                    """
                    // build br.test for integration test
                    // only build binarys if not exist, use the cached binarys if exist
                    sh label: "prepare", script: """
                        [ -f ./bin/tidb-server ] || (make failpoint-enable && make && make failpoint-disable)
                        [ -f ./bin/br.test ] || make build_for_br_integration_test
                        ls -alh ./bin
                        ./bin/tidb-server -V
                    """
                    dir("bin") {
                        container("utils") {
                            retry(2) {
                                sh label: "download third_party", script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                        --pd=${params.RELEASE_TAG} \
                                        --pd-ctl=${params.RELEASE_TAG} \
                                        --tikv=${params.RELEASE_TAG} \
                                        --tikv-ctl=${params.RELEASE_TAG} \
                                        --tiflash=${params.RELEASE_TAG} \
                                        --ycsb=${OCI_TAG_YCSB} \
                                        --fake-gcs-server=${OCI_TAG_FAKE_GCS_SERVER} \
                                        --kes=${OCI_TAG_KES} \
                                        --minio=${OCI_TAG_MINIO} \
                                        --brv408
                                """
                            }
                        }
                        sh """
                            mv tiflash tiflash_dir
                            ln -s tiflash_dir/tiflash tiflash
                            ls -alh .
                            ./pd-server -V
                            ./tikv-server -V
                            ./tiflash --version
                        """
                    }
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/br-tests") {
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
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08'
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
                    stage("Test") {
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/br-tests") {
                                    sh label: "TEST_GROUP ${TEST_GROUP}", script: """#!/usr/bin/env bash
                                        chmod +x br/tests/*.sh
                                        ./br/tests/run_group_br_tests.sh ${TEST_GROUP}
                                    """
                                }
                            }
                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/backup_restore_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/backup_restore_test/ -type f -name "*.log")
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
