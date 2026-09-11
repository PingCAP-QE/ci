// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb-tools'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb-tools/latest/pod-pull_verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

// Server/dumpling binaries are pulled from OCI artifact packages instead of the
// (decommissioned) file server. Tags follow the tidb-tools PR base branch, and
// PR titles may pin a peer component (e.g. "... | tidb=release-8.5") which
// computeArtifactOciTagFromPR resolves per component.
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_DUMPLING = component.computeArtifactOciTagFromPR('dumpling', REFS.base_ref, REFS.pulls[0].title, 'master')

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            retries 2
            defaultContainer 'runner'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = "${env._JENKINS_OCI_ARTIFACT_HOST_HUB}"
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        script {
                            retry(2) {
                                prow.checkoutRefs(REFS, credentialsId = '', timeout = 5, withSubmodule = true, gitBaseUrl = 'https://github.com')
                            }
                        }
                    }
                }
            }
        }
        stage('Build') {
            steps {
                // sh "GOOS=darwin GOARCH=amd64 make build -C ${REFS.repo}"
                // sh "GOOS=darwin GOARCH=arm64 make build -C ${REFS.repo}"
                sh "GOOS=linux GOARCH=arm64 make build -C ${REFS.repo}"
                sh "GOOS=linux GOARCH=amd64 make build -C ${REFS.repo}" // be the last order to build, the unit test will use it.
            }
        }
        stage('Unit Test') {
            steps {
                sh label: 'test mysql connection', script: """
                for i in {1..10}; do
                    if mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; then
                        break
                    elif [ \$i -eq 10 ]; then
                        exit 2
                    fi
                    sleep 1
                done
                """
                sh label: 'test', script: "MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 make test -C ${REFS.repo}"
            }
        }
        stage('Integration Test') {
            steps {
                dir("tidb-tools") {
                    container('utils') {
                        dir('bin') {
                            sh label: 'download tidb/tikv/pd/dumpling from OCI', script: """
                            ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                --tidb=${OCI_TAG_TIDB} --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV} --dumpling=${OCI_TAG_DUMPLING}
                            """
                        }
                    }
                    sh label: "download enterprise-tools-nightly", script: """
                        curl -fsSL --retry 3 -o tidb-enterprise-tools-nightly-linux-amd64.tar.gz https://download.pingcap.com/tidb-enterprise-tools-nightly-linux-amd64.tar.gz
                        tar -xzf tidb-enterprise-tools-nightly-linux-amd64.tar.gz
                        mv tidb-enterprise-tools-nightly-linux-amd64/bin/loader bin/
                        rm -r tidb-enterprise-tools-nightly-linux-amd64
                    """
                    sh label: "check", script: """
                        which bin/tikv-server
                        which bin/pd-server
                        which bin/tidb-server
                        which bin/dumpling
                        which bin/importer
                        ls -alh ./bin/
                        ./bin/dumpling --version
                        ./bin/tikv-server -V
                        ./bin/pd-server -V
                        ./bin/tidb-server -V
                    """
                    sh label: 'integration test', script: """
                    for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                    export MYSQL_HOST="127.0.0.1"
                    export MYSQL_PORT=3306
                    make integration_test
                    """
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
