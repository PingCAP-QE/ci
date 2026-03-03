// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'PingCAP-QE/tidb-test'
final POD_TEMPLATE_FILE = 'pipelines/pingcap-qe/tidb-test/release-6.0/pod-ghpr_mysql_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 45, unit: 'MINUTES')
    }
    stages {
        stage('Debug info') {
            // options { }  Valid option types: [cache, catchError, checkoutToSubdirectory, podTemplate, retry, script, skipDefaultCheckout, timeout, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, wrap, ws]
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    go env
                    echo "-------------------------"
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', REFS.base_ref, REFS.pulls[0].title, trunkBranch=REFS.base_ref, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tidb-test") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-server") {
                        // FIXME: https://github.com/PingCAP-QE/tidb-test/issues/1987
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                }
                dir('tidb-test') {
                    cache(path: "./mysql_test", includes: '**/*', key: "ws/tidb-test/mysql-test/rev-${REFS.pulls[0].sha}") {
                        sh "touch ws-${BUILD_TAG}"
                    }
                }
            }
        }
        stage('MySQL Tests') {
            matrix {
                axes {
                    axis {
                        name 'CACHE_ENABLED'
                        values '0', "1"
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
                                cache(path: "./bin", includes: '**/*', key: "ws/${BUILD_TAG}/tidb-server") {
                                    sh label: 'tidb-server', script: 'ls bin/tidb-server && chmod +x bin/tidb-server && ./bin/tidb-server -V'
                                }
                            }
                            dir('tidb-test/mysql_test') {
                                cache(path: "./", includes: '**/*', key: "ws/tidb-test/mysql-test/rev-${REFS.pulls[0].sha}") {
                                    sh label: "CACHE_ENABLED ${CACHE_ENABLED}", script: """
                                    export TIDB_SERVER_PATH=${WORKSPACE}/tidb/bin/tidb-server
                                    export CACHE_ENABLED=${CACHE_ENABLED}
                                    export TIDB_TEST_STORE_NAME="unistore"
                                    ./test.sh
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
