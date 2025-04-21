// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-periodics_tidb_next_gen_smoke_test.yaml'
final TARGET_BRANCH_TIDB = "feature/next-gen-tidb"
final TARGET_BRANCH_PD = "master"
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
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${TARGET_BRANCH_TIDB}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', TARGET_BRANCH_TIDB, "", trunkBranch=TARGET_BRANCH_TIDB, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("pd") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/pd/rev-${TARGET_BRANCH_PD}", restoreKeys: ['git/pingcap/pd/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/pd.git', 'pd', TARGET_BRANCH_PD, "", trunkBranch=TARGET_BRANCH_PD, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tikv") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tikv/rev-${TARGET_BRANCH_TIKV}", restoreKeys: ['git/pingcap/tikv/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/tidbcloud/cloud-storage-engine.git', 'tikv', TARGET_BRANCH_TIKV, "", trunkBranch=TARGET_BRANCH_TIKV, timeout=5, credentialsId="")
                            }   
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: "build tidb", script: """
                        export NEXT_GEN=1
                        make build
                    """
                }
                dir('pd') {
                    sh label: "build pd", script: """
                        make build
                    """
                }   
                dir('tikv') {
                    container('rust') { 
                        sh label: "build tikv", script: """
                            make dist_release
                        """
                    }
                }
            }
        }
        stage('Tests') {
            steps {
                dir('smoke_test') {
                    sh label: "run tests", script: """
                        cp -r ../tidb/bin/* ./bin/
                        cp -r ../pd/bin/* ./bin/
                        cp -r ../tikv/bin/* ./bin/
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                        # TODO: add some tests here, tiup to start tidb cluster and run some tests
                    """
                }
            }
        }
    }
}
