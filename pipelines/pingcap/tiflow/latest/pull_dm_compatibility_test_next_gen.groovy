// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_compatibility_test_next_gen.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
def skipRemainingStages = false

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "dedicated-next-gen")

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/tidbx'
        NEXT_GEN = 1
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
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
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} -c golang -- bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
        stage('Check diff files') {
            steps {
                container("golang") {
                    script {
                        def pr_diff_files = component.getPrDiffFiles(GIT_FULL_REPO_NAME, REFS.pulls[0].number, GIT_CREDENTIALS_ID2)
                        def pattern = /(^dm\/|^pkg\/|^go\.mod).*$/
                        println "pr_diff_files: ${pr_diff_files}"
                        // if any diff files start with dm/ or pkg/ or file go.mod, run the dm compatibility test
                        def matched = component.patternMatchAnyFile(pattern, pr_diff_files)
                        if (matched) {
                            println "matched, some diff files full path start with dm/ or pkg/ or go.mod, run the dm compatibility test"
                        } else {
                            echo "not matched, all files full path not start with dm/ or pkg/ or go.mod, current pr not releate to dm, so skip the dm compatibility test"
                            currentBuild.result = 'SUCCESS'
                            skipRemainingStages = true
                            return // skip the remaining stages
                        }
                    }
                }
            }
        }
        stage('Checkout') {
            when { expression { !skipRemainingStages} }
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
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
        stage("prepare") {
            when { expression { !skipRemainingStages} }
            options { timeout(time: 35, unit: 'MINUTES') }
            steps {
                dir("tiflow") {
                    script {
                        retry(2) {
                            sh label: "build previous", script: """
                                echo "build binary for previous version"
                                git fetch origin ${REFS.base_ref}:local
                                git checkout local
                                git rev-parse HEAD
                                make dm_integration_test_build
                                mv bin/dm-master.test bin/dm-master.test.previous
                                mv bin/dm-worker.test bin/dm-worker.test.previous
                                ls -alh ./bin/
                            """
                            sh label: "build current", script: """
                                echo "build binary for current version"
                                // reset to current version
                                git checkout ${REFS.pulls[0].sha}
                                make dm_integration_test_build
                                mv bin/dm-master.test bin/dm-master.test.current
                                mv bin/dm-worker.test bin/dm-worker.test.current
                                ls -alh ./bin/
                            """
                        }
                    }
                    // download next-gen tidb components via OCI artifacts
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download next-gen tidb components", script: """
                                        export script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        chmod +x \$script
                                        \$script \
                                            --tidb=${OCI_TAG_TIDB} \
                                            --minio=RELEASE.2025-07-23T15-54-02Z
                                        ls -alh
                                    """
                                }
                            }
                        }
                    }
                    // download mydumper and gh-ost
                    sh label: "download extra tools", script: """
                        wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O /tmp/mydumper.tar.gz http://download.pingcap.org/tidb-enterprise-tools-latest-linux-amd64.tar.gz
                        tar -xz -C /tmp -f /tmp/mydumper.tar.gz tidb-enterprise-tools-latest-linux-amd64/bin/mydumper
                        mv /tmp/tidb-enterprise-tools-latest-linux-amd64/bin/mydumper ./bin/
                        rm -rf /tmp/mydumper.tar.gz /tmp/tidb-enterprise-tools-latest-linux-amd64

                        wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O /tmp/gh-ost.tar.gz https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz
                        tar -xz -C ./bin -f /tmp/gh-ost.tar.gz
                        rm -f /tmp/gh-ost.tar.gz
                        chmod +x ./bin/gh-ost ./bin/mydumper
                    """
                    sh label: "verify binaries", script: """
                        ls -alh ./bin
                        ./bin/tidb-server -V
                        ./bin/mydumper -V
                    """
                }
            }
        }
        stage("Test") {
            when { expression { !skipRemainingStages} }
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir('tiflow') {
                        timeout(time: 10, unit: 'MINUTES') {
                            sh label: "wait mysql ready", script: """
                                pwd && ls -alh
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            """
                        }
                        sh label: "test", script: """
                            export MYSQL_HOST1=127.0.0.1
                            export MYSQL_PORT1=3306
                            export MYSQL_HOST2=127.0.0.1
                            export MYSQL_PORT2=3307
                            export PATH=/usr/local/go/bin:\$PATH
                            make dm_compatibility_test CASE=""
                        """
                }
            }
            post {
                failure {
                    sh label: "collect logs", script: """
                        ls /tmp/dm_test
                        tar -cvzf log.tar.gz \$(find /tmp/dm_test/ -type f -name "*.log")
                        ls -alh  log.tar.gz
                    """
                    archiveArtifacts artifacts: "log.tar.gz", allowEmptyArchive: true
                }
            }
        }
    }
}
