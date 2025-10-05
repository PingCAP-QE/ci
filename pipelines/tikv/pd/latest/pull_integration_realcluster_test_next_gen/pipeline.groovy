// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final GIT_FULL_REPO_NAME = 'tikv/pd'
final K8S_NAMESPACE = "jenkins-pd"
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

final TARGET_BRANCH_TIDB = "master"
final TARGET_BRANCH_TIFLASH = "master"
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
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("pd") {
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
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('ng-binary', REFS, "pd-server")) {
                        sh label: 'pd-server', script: '[ -f bin/pd-server ] || WITH_RACE=1 RUN_CI=1 make pd-server-basic'
                        sh 'bin/pd-server -V'
                    }
                    dir('third_bin') {
                        container("utils") {
                            sh """
                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} \
                                    --tidb=${TARGET_BRANCH_TIDB}-next-gen \
                                    --tikv=${TARGET_BRANCH_TIKV}-next-gen \
                                    --tikv-worker=${TARGET_BRANCH_TIKV}-next-gen \
                                    --tiflash=${TARGET_BRANCH_TIFLASH}-next-gen \
                                    --minio=RELEASE.2025-07-23T15-54-02Z
                            """
                        }
                        sh '''
                            mv tiflash tiflash_dir
                            ln -s `pwd`/tiflash_dir/tiflash tiflash

                            ./tikv-server -V
                            ./tikv-worker -V
                            ./tidb-server -V
                            ./tiflash --version
                        '''
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    sh 'make test-real-cluster'
                }
            }
            post {
                failure {
                    sh label: "collect logs", script: """
                        ls /tmp/real_cluster/playground
                        tar -cvzf tiup-playground-output.tar.gz \$(find /tmp/real_cluster/playground -maxdepth 2 -type f -name "*.log")
                        ls -alh tiup-playground-output.tar.gz

                        tar -cvzf log-real-cluster-data.tar.gz /home/jenkins/.tiup/data
                        ls -alh log-real-cluster-data.tar.gz
                    """
                    archiveArtifacts artifacts: "tiup-playground-output.tar.gz, log-real-cluster-data.tar.gz", fingerprint: true
                }
            }
        }
    }
}
