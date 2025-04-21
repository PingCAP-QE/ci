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
        timeout(time: 120, unit: 'MINUTES')
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
                    cache(path: "./", includes: '**/*', key: "git/tikv/pd/rev-${TARGET_BRANCH_PD}", restoreKeys: ['git/tikv/pd/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/tikv/pd.git', 'pd', TARGET_BRANCH_PD, "", trunkBranch=TARGET_BRANCH_PD, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tikv") {
                    cache(path: "./", includes: '**/*', key: "git/tidbcloud/cloud-storage-engine/rev-${TARGET_BRANCH_TIKV}", restoreKeys: ['git/tidbcloud/cloud-storage-engine/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('git@github.com:tidbcloud/cloud-storage-engine.git', 'tikv', TARGET_BRANCH_TIKV, "", trunkBranch=TARGET_BRANCH_TIKV, timeout=5, credentialsId=GIT_CREDENTIALS_ID)
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
                        set -e
                        export NEXT_GEN=1
                        make build
                    """
                }
                dir('pd') {
                    sh label: "build pd", script: """
                        set -e
                        make build
                    """
                }   
                dir('tikv') {
                    container('rust') { 
                        sh label: "build tikv", script: """
                            set -e
                            make dist_release
                        """
                    }
                }
            }
        }
        stage('Tests') {
            steps {
                dir('smoke_test') {
                    sh label: "start tidb cluster", script: """
                        set -ex # Exit on error, print commands

                        mkdir -p ./bin
                        cp ../tidb/bin/tidb-server ./bin/
                        cp ../pd/bin/pd-server ./bin/
                        cp ../tikv/bin/tikv-server ./bin/
                        
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V

                        # Ensure tiup and mysql client are installed in agent environment
                        # If not, you might need:
                        # curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
                        # source /root/.profile # Or appropriate path for the user
                        # apt-get update && apt-get install -y mysql-client # Example for Debian/Ubuntu

                        # Start cluster using tiup playground with local binaries
                        echo "Starting TiDB cluster with tiup playground..."
                        tiup playground \\
                          --pd.binpath ./bin/pd-server \\
                          --kv.binpath ./bin/tikv-server \\
                          --db.binpath ./bin/tidb-server \\
                          --pd 1 --kv 1 --db 1 --monitor=false \\
                          --tag smoke-test & # Run in background with a specific tag
                    """
                    sh label: "run tests", script: """
                        set -ex # Exit on error, print commands
                        # Wait for cluster to be ready
                        echo "Waiting for cluster to start (30s)..."
                        sleep 30

                        # Run smoke test SQL commands
                        echo "Running smoke tests..."
                        mysql -h 127.0.0.1 -P 4000 -u root --connect-timeout=10 --execute " \\
                          SHOW DATABASES; \\
                          CREATE DATABASE IF NOT EXISTS smoke_test_db; \\
                          USE smoke_test_db; \\
                          CREATE TABLE IF NOT EXISTS smoke_test_table (id INT PRIMARY KEY); \\
                          INSERT INTO smoke_test_table (id) VALUES (1), (2), (3) ON DUPLICATE KEY UPDATE id=id; \\
                          SELECT COUNT(*) FROM smoke_test_table; \\
                          DROP DATABASE IF EXISTS smoke_test_db; \\
                        "

                        # Stop the playground cluster
                        echo "Stopping TiDB cluster..."
                        tiup clean smoke-test --all # Clean up the specific playground instance

                        echo "Smoke test completed successfully."
                    """
                }
            }
        }
    }
}
