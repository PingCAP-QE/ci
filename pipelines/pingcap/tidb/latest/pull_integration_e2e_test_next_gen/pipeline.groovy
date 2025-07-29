
// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final TARGET_BRANCH_PD = "master"
final TARGET_BRANCH_TIFLASH = "master"
final TARGET_BRANCH_TICDC = "master"
final TARGET_BRANCH_TIKV = "dedicated"

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        NEXT_GEN = '1' // enable build and test for Next Gen kernel type.
        OCI_ARTIFACT_HOST = 'hub-mig.pingcap.net'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                git.setSshKey(GIT_CREDENTIALS_ID)
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir("${REFS.repo}/tests/integrationtest2/third_bin") {
                    container("utils") {
                        sh """
                            script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                            chmod +x \$script
                            \${script} \
                            --pd=${TARGET_BRANCH_PD}-next-gen \
                            --tikv=${TARGET_BRANCH_TIKV}-next-gen \
                            --tikv-worker=${TARGET_BRANCH_TIKV}-next-gen \
                            --tiflash=${TARGET_BRANCH_TIFLASH}-next-gen \
                            --ticdc-new=${TARGET_BRANCH_TICDC}
                        """
                    }
                }
            }
        }
        stage('Tests') {
            steps {
                dir("${REFS.repo}/tests/integrationtest2") {
                    sh '''
                        cd third_bin
                        mv tiflash tiflash_dir
                        ln -s `pwd`/tiflash_dir/tiflash tiflash
                        ./tikv-server -V
                        ./pd-server -V
                        ./tiflash --version
                        ./cdc version
                    '''
                    sh label: 'test', script: './run-tests.sh'
                }
            }
            post{
                failure {
                    script {
                        println "Test failed, archive the log"
                        archiveArtifacts artifacts: "${REFS.repo}/tests/integrationtest2/logs", fingerprint: true
                    }
                }
            }
        }
    }
}
