// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-periodics_tidb_next_gen_smoke_test.yaml'
final OCI_TAG_TIDB = "master"
final OCI_TAG_PD = "master"
final OCI_TAG_TIKV = "dedicated"

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
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${OCI_TAG_TIDB}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/pingcap/tidb.git', 'tidb', OCI_TAG_TIDB, "", trunkBranch=OCI_TAG_TIDB, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("pd") {
                    cache(path: "./", includes: '**/*', key: "git/tikv/pd/rev-${OCI_TAG_PD}", restoreKeys: ['git/tikv/pd/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutWithMergeBase('https://github.com/tikv/pd.git', 'pd', OCI_TAG_PD, "", trunkBranch=OCI_TAG_PD, timeout=5, credentialsId="")
                            }
                        }
                    }
                }
                dir("tikv") {
                    cache(path: "./", includes: '**/*', key: "git/tidbcloud/cloud-storage-engine/rev-${OCI_TAG_TIKV}", restoreKeys: ['git/tidbcloud/cloud-storage-engine/rev-']) {
                        retry(2) {
                            script {
                                component.checkoutSupportBatch('git@github.com:tidbcloud/cloud-storage-engine.git', 'tikv', OCI_TAG_TIKV, "", [], GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tikv') {
                    container('rust') {
                        // Cache cargo registry and git dependencies
                        // Use OCI_TAG_TIKV and the pipeline file name in the key for better scoping
                        script {
                            def pipelineIdentifier = env.JOB_BASE_NAME ?: "periodics-tidb-next-gen-smoke-test"
                            // Clean workspace .cargo directory before attempting cache restore/build
                            sh "rm -rf .cargo"
                            // Cache paths relative to the current workspace directory ('tikv')
                            cache(path: ".cargo/git", key: "cargo-git-ws-tikv-next-gen-${OCI_TAG_TIKV}-${pipelineIdentifier}") {
                                sh label: "build tikv", script: """
                                    set -e
                                    source /opt/rh/devtoolset-10/enable
                                    cmake --version
                                    protoc --version
                                    gcc --version

                                    # Set CARGO_HOME to a path within the workspace for caching
                                    export CARGO_HOME="\$(pwd)/.cargo"
                                    echo "Set CARGO_HOME to: \${CARGO_HOME}"

                                    mkdir -vp "\${CARGO_HOME}"

                                    # Create cargo config inside the workspace CARGO_HOME
                                    cat << EOF | tee "\${CARGO_HOME}/config.toml"
[source.crates-io]
replace-with = 'aliyun'
[source.aliyun]
registry = "sparse+https://mirrors.aliyun.com/crates.io-index/"
EOF
                                    echo "Current CARGO_HOME is: \${CARGO_HOME}"
                                    echo "Listing cached directories before build (relative path):"
                                    ls -lad "\${CARGO_HOME}/registry" || echo "Registry cache miss or empty."
                                    ls -lad "\${CARGO_HOME}/git" || echo "Git cache miss or empty."

                                    make release

                                    echo "Listing cached directories after build (relative path):"
                                    du -sh "\${CARGO_HOME}/registry" || echo "Registry dir not found."
                                    du -sh "\${CARGO_HOME}/git" || echo "Git dir not found."

                                    # Ensure all filesystem writes are flushed before cache archiving begins
                                    sync
                                """
                            }
                        }
                    }
                }
                dir('tidb') {
                    sh label: "build tidb", script: """
                        set -e
                        export NEXT_GEN=1
                        make server
                    """
                }
                dir('pd') {
                    sh label: "build pd", script: """
                        set -e
                        make build
                    """
                }
            }
        }
        stage('Tests') {
            steps {
                dir('smoke_test') {
                    sh label: "start tidb cluster", script: """
                        set -ex

                        mkdir -p ./bin
                        cp ../tidb/bin/tidb-server ./bin/
                        cp ../pd/bin/pd-server ./bin/
                        cp ../tikv/target/release/tikv-server ./bin/

                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V

                        # Ensure tiup and mysql client are installed in agent environment
                        curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
                        source ~/.bash_profile  && which tiup

                        echo "Starting TiDB cluster with tiup playground..."
                        tiup playground \\
                          --pd.binpath ./bin/pd-server \\
                          --kv.binpath ./bin/tikv-server \\
                          --db.binpath ./bin/tidb-server \\
                          --pd 1 --kv 1 --db 1 --without-monitor \\
                          --tag smoke-test > playground.log 2>&1 &
                    """
                    sh label: "run tests", script: """
                        set -ex
                        echo "Waiting for cluster to start (30s)..."
                        sleep 30
                        echo "--- Displaying playground startup log (playground.log) ---"
                        cat playground.log || true
                        echo "--- End of playground startup log ---"
                        export PATH=\$PATH:\$HOME/.tiup/bin && which tiup

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

                        echo "Stopping TiDB cluster..."
                        tiup clean smoke-test --all

                        echo "Smoke test completed successfully."
                    """
                }
            }
            post {
                unsuccessful {
                    sh label: "Archive Logs on Failure", script: """
                        set +e
                        mkdir -p smoke_test/logs
                        cp smoke_test/playground.log smoke_test/logs/playground.log || echo "Failed to copy playground.log"
                        TIUP_DATA_DIR=\${TIUP_HOME:-\$HOME/.tiup}/data/smoke-test
                        if [ -d "\$TIUP_DATA_DIR" ]; then
                            cp "\$TIUP_DATA_DIR"/*/*.log smoke_test/logs/ || echo "Failed to copy component logs"
                        else
                            echo "TIUP data directory \$TIUP_DATA_DIR not found."
                        fi
                        echo "--- Log files in archive target directory: ---"
                        ls -alh smoke_test/logs/
                    """
                    archiveArtifacts(artifacts: 'smoke_test/logs/*.log', allowEmptyArchive: true)
                }
            }
        }
    }
}
