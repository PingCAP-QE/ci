// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final K8S_NAMESPACE = "jenkins-tidb"
final SELF_DIR = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}"
final POD_TEMPLATE_FILE = "${SELF_DIR}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir('tidb') {
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
        stage('Prepare') {
            steps {
                dir('tidb') {
                    script {
                        // Computes the branch name for downloading binaries based on the PR target branch and title.
                        def otherComponentBranch = component.computeBranchFromPR('other', REFS.base_ref, REFS.pulls[0].title, 'master')
                        def minioTag = 'RELEASE.2025-07-23T15-54-02Z'
                        retry(3) {
                            dir('tests/integrationtest2/third_bin') {
                                cache(path: "./", includes: '**/*', key: "binary/tidb/integrationtest2/third_bin/${otherComponentBranch}/${minioTag}") {
                                    container("utils") {
                                        sh label: 'download binary', script: """
                                            if [[ -x tici-server && -x tikv-server && -x pd-server && -x tiflash && -x cdc && -x minio && -x mc ]]; then
                                                echo "third_bin cache hit; skip download."
                                                exit 0
                                            fi

                                            script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                            chmod +x \$script
                                            \$script \
                                                --pd=${otherComponentBranch} \
                                                --tikv=${otherComponentBranch} \
                                                --tiflash=${otherComponentBranch} \
                                                --ticdc=${otherComponentBranch} \
                                                --tici=${otherComponentBranch} \
                                                --minio=${minioTag}
                                        """
                                    }
                                    sh '''
                                        mv tiflash tiflash_dir
                                        ln -s `pwd`/tiflash_dir/tiflash tiflash
                                        ls -alh .
                                        ./tikv-server -V
                                        ./pd-server -V
                                        ./tiflash --version
                                        ./tici-server -V
                                        ./cdc version
                                    '''
                                }
                            }
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            environment {
                TICI_BIN = "third_bin/tici-server"
                MINIO_BIN = "third_bin/minio"
                MINIO_MC_BIN = "third_bin/mc"
            }
            steps {
                dir('tidb') {
                    sh label: 'test', script: """
                        cd tests/integrationtest2 && ./run-tests.sh -t tici/tici_integration
                    """
                }
            }
            post{
                failure {
                    script {
                        archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}
