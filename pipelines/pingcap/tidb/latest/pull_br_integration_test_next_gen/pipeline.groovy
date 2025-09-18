// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final SELF_DIR = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}"
final POD_TEMPLATE_FILE = "${SELF_DIR}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final TARGET_BRANCH_PD = "master"
final TARGET_BRANCH_TIFLASH = "master"
final TARGET_BRANCH_TIKV = "dedicated"

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
        OCI_ARTIFACT_HOST = 'hub-mig.pingcap.net'
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
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
        stage("Prepare") {
            steps {
                dir(REFS.repo) {
                    sh label: 'tidb-server failpoint binary', script: 'make failpoint-enable server failpoint-disable'
                    sh label: 'br test binary', script: 'make build_for_br_integration_test'
                    dir('bin') {
                        container("utils") {
                            sh """
                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} \
                                    --pd=${TARGET_BRANCH_PD}-next-gen \
                                    --tikv=${TARGET_BRANCH_TIKV}-next-gen \
                                    --tikv-worker=${TARGET_BRANCH_TIKV}-next-gen \
                                    --tiflash=${TARGET_BRANCH_TIFLASH}-next-gen
                            """
                        }
                        sh '''
                            mv tiflash tiflash_dir
                            ln -s `pwd`/tiflash_dir/tiflash tiflash

                            ./tikv-server -V
                            ./tikv-worker -V
                            ./pd-server -V
                            ./tiflash --version
                        '''
                        sh "${WORKSPACE}/${SELF_DIR}/download_tools.sh ${FILE_SERVER_URL}"
                    }
                    // cache it for other pods
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch rev-${REFS.pulls[0].sha}"
                    }
                }
            }
        }
        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06',  'G07', 'G08', 'others'
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
                        environment { CODECOV_TOKEN = credentials('codecov-token-tidb') }
                        options { timeout(time: 45, unit: 'MINUTES') }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${REFS.pulls[0].sha}" // will fail when not found in cache or no cached.
                                }
                                sh label: "TEST_GROUP ${TEST_GROUP}", script: "chmod +x br/tests/*.sh && ./br/tests/run_group_br_tests.sh ${TEST_GROUP}"
                            }
                        }
                        post{
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/backup_restore_test
                                    tar --warning=no-file-changed -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/backup_restore_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", fingerprint: true
                            }
                            success {
                                dir(REFS.repo) {
                                    sh 'ls -alh /tmp/group_cover && gocovmerge /tmp/group_cover/cov.* > coverage.txt'
                                    script {
                                        prow.uploadCoverageToCodecov(REFS, 'integration', './coverage.txt')
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
