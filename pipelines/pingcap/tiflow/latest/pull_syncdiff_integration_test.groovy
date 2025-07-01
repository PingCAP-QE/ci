// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_syncdiff_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
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
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            retry(2) {
                                prow.checkoutRefs(REFS, timeout = 5, credentialsId = '', gitBaseUrl = 'https://github.com', withSubmodule=true)
                            }
                        }
                    }
                }
            }
        }
        stage('Integration Test') {
            steps {
                dir(REFS.repo) {
                    script {
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'dumpling', REFS.base_ref, REFS.pulls[0].title, 'centos7/dumpling.tar.gz', 'bin')
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', REFS.base_ref, REFS.pulls[0].title, 'centos7/tikv-server.tar.gz', 'bin')
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', REFS.base_ref, REFS.pulls[0].title, 'centos7/pd-server.tar.gz', 'bin')
                        component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tidb', REFS.base_ref, REFS.pulls[0].title, 'centos7/tidb-server.tar.gz', 'bin')
                    }
                    sh label: "download enterprise-tools", script: """
                        # The current internal cache is from the address http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz, and this content has stopped updating.
                        wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O tidb-enterprise-tools.tar.gz ${FILE_SERVER_URL}/download/ci-artifacts/tiflow/linux-amd64/v20220531/tidb-enterprise-tools.tar.gz
                        tar -xzf tidb-enterprise-tools.tar.gz
                        mv tidb-enterprise-tools/bin/loader bin/
                        mv tidb-enterprise-tools/bin/importer bin/
                        rm -r tidb-enterprise-tools
                    """
                    sh label: "check", script: """
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
                    sh label: 'sync_diff_inspector integration test', script: """
                    for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                    export MYSQL_HOST="127.0.0.1"
                    export MYSQL_PORT=3306
                    make failpoint-enable
                    make sync-diff-inspector
                    make failpoint-disable
                    cd sync_diff_inspector && ln -sf ../bin . && ./tests/run.sh
                    """
                }
            }
            post{
                unsuccessful {
                    sh label: 'archive logs', script: """
                    tar --warning=no-file-changed  -cvzf logs.tar.gz \$(find /tmp/sync_diff_inspector_test/ -type f -name "*.log")
                    tar --warning=no-file-changed  -cvzf fix_sqls.tar.gz \$(find /tmp/sync_diff_inspector_test/sync_diff_inspector/output/fix-on-tidb/ -type f -name "*.sql")
                    """
                    archiveArtifacts artifacts: "logs.tar.gz", fingerprint: true
                    archiveArtifacts artifacts: "fix_sqls.tar.gz", fingerprint: true
                    sh label: 'print logs', script:'''
                        find /tmp/sync_diff_inspector_test -name "*.log" | xargs -I {} bash -c 'echo "**************************************"; echo "{}"; cat "{}"'
                        echo ""
                        echo "******************sync_diff.log********************"
                        cat /tmp/sync_diff_inspector_test/sync_diff_inspector/output/sync_diff.log
                        echo "********************fix.sql********************"
                        find /tmp/sync_diff_inspector_test/sync_diff_inspector/output/fix-on-tidb -name "*.sql" | xargs -I {} bash -c 'echo "**************************************"; echo "{}"; cat "{}"'
                    '''
                }
            }
        }
    }
}
