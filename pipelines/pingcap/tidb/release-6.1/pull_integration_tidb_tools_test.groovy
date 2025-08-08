// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-6.1 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.1/pod-pull_integration_tidb_tools_test.yaml'
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
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
        GITHUB_TOKEN = credentials('github-bot-token')
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
                dir("tidb") {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
                dir("tidb-tools") {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb-tools/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb-tools/rev-']) {
                        retry(2) {
                            script {
                                // from v6.0.0, tidb-tools only maintain master branch
                                component.checkout('https://github.com/pingcap/tidb-tools.git', 'tidb-tools', "master", REFS.pulls[0].title, "")
                            }
                        }
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir('tidb') {
                    sh label: 'build', script: """
                        make server
                        make build_dumpling
                        ls -alh bin/
                    """
                }
                dir('tidb-tools') {
                    script {
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin')
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin')
                    }
                    sh label: "download enterprise-tools-nightly", script: """
                        wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O tidb-enterprise-tools-nightly-linux-amd64.tar.gz https://download.pingcap.org/tidb-enterprise-tools-nightly-linux-amd64.tar.gz
                        tar -xzf tidb-enterprise-tools-nightly-linux-amd64.tar.gz
                        mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/
                        rm -r tidb-enterprise-tools-nightly-linux-amd64
                    """
                    container(name: 'golang121') {
                        sh label: "build", script: """
                            make build
                        """
                    }
                    sh label: "check", script: """
                        cp ../tidb/bin/tidb-server bin/
                        cp ../tidb/bin/dumpling bin/
                        which bin/tikv-server
                        which bin/pd-server
                        which bin/tidb-server
                        which bin/dumpling
                        which bin/importer
                        ls -alh ./bin/
                        chmod +x bin/*
                        ./bin/dumpling --version
                        ./bin/tikv-server -V
                        ./bin/pd-server -V
                        ./bin/tidb-server -V
                    """
                }
            }
        }
        stage('TiDB Tools Tests') {
            steps {
                dir('tidb-tools') {
                    container(name: 'golang121') {
                        sh label: 'integration test', script: """
                            for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                            export MYSQL_HOST="127.0.0.1"
                            export MYSQL_PORT=3306
                            make integration_test
                        """
                    }
                }
            }
            post{
                unsuccessful {
                    sh label: 'archive logs', script: """
                    tar --warning=no-file-changed  -cvzf logs.tar.gz \$(find /tmp/tidb_tools_test/ -type f -name "*.log")
                    tar --warning=no-file-changed  -cvzf fix_sqls.tar.gz \$(find /tmp/tidb_tools_test/sync_diff_inspector/output/fix-on-tidb/ -type f -name "*.sql")
                    """
                    archiveArtifacts artifacts: "logs.tar.gz", fingerprint: true
                    archiveArtifacts artifacts: "fix_sqls.tar.gz", fingerprint: true

                    sh label: 'print logs', script:'''
                        find /tmp/tidb_tools_test -name "*.log" | xargs -I {} bash -c 'echo "**************************************"; echo "{}"; cat "{}"'
                        echo ""
                        echo "******************sync_diff.log********************"
                        cat /tmp/tidb_tools_test/sync_diff_inspector/output/sync_diff.log
                        echo "********************fix.sql********************"
                        find /tmp/tidb_tools_test/sync_diff_inspector/output/fix-on-tidb -name "*.sql" | xargs -I {} bash -c 'echo "**************************************"; echo "{}"; cat "{}"'
                    '''
                }
            }
        }
    }
}
