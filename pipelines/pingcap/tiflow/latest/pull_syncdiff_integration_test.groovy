// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_syncdiff_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_DUMPLING = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
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
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
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
                    container("utils") {
                        dir("bin") {
                            retry(2) {
                                sh label: "download third-party binaries", script: """
                                    ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh \
                                        --tidb=${OCI_TAG_TIDB} \
                                        --tikv=${OCI_TAG_TIKV} \
                                        --pd=${OCI_TAG_PD} \
                                        --dumpling=${OCI_TAG_DUMPLING}
                                """
                            }
                        }
                    }
                    sh label: "check", script: """
                        which bin/tikv-server
                        which bin/pd-server
                        which bin/tidb-server
                        which bin/dumpling
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
