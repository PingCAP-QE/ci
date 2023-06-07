// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/pd'
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/latest/pod-pull_integration_copr_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("pd") {
                    cache(path: "./", filter: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb") {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.base_sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, '')
                            }
                        }
                    }
                }
                dir("tikv-copr-test") {
                    cache(path: "./", filter: '**/*', key: "git/tikv/copr-test/rev-${REFS.base_sha}", restoreKeys: ['git/tikv/copr-test/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('https://github.com/tikv/copr-test.git', 'tikv-copr-test', REFS.base_ref, REFS.pulls[0].title, '')
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('pd') {
                    container("golang") {
                        sh label: 'pd-server', script: '[ -f bin/pd-server ] || make'
                        sh label: 'tikv-server', script: """
                        chmod +x ${WORKSPACE}/scripts/artifacts/*.sh
                        ${WORKSPACE}/scripts/artifacts/download_pingcap_artifact.sh --tikv=${REFS.base_ref}
                        rm -rf third_bin/bin && mv third_bin/* bin/ && ls -alh bin/
                        bin/pd-server -V
                        bin/tikv-server -V
                        """
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                //  TODO: open pull request to support tidb-server bin path in copr-test
                //   https://github.com/tikv/copr-test/tree/master
                dir('tikv-copr-test') {
                    sh label: "Push Down Test", script: """
                        pd_bin=${WORKSPACE}/pd/bin/pd-server \
                        tikv_bin=${WORKSPACE}/pd/bin/tikv-server \
                        tidb_src_dir=${WORKSPACE}/tidb \
                        make push-down-test
                    """
                }
            }
        }
    }
}
