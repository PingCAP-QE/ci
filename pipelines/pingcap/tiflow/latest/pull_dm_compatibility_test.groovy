// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_compatibility_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'master'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'
def skipRemainingStages = false

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
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
            steps {
                dir("tiflow") {
                    script {
                        retry(2) {
                            sh label: "build previous", script: """
                                echo "build binary for previous version"
                                git checkout ${REFS.base_sha}
                                make dm_integration_test_build
                                mv bin/dm-master.test bin/dm-master.test.previous
                                mv bin/dm-worker.test bin/dm-worker.test.previous
                                ls -alh ./bin/
                            """
                            sh label: "build current", script: """
                                echo "build binary for current version"
                                # reset to current version
                                git checkout ${REFS.pulls[0].sha}
                                make dm_integration_test_build
                                mv bin/dm-master.test bin/dm-master.test.current
                                mv bin/dm-worker.test bin/dm-worker.test.current
                                ls -alh ./bin/
                            """
                            sh label: "prepare third_party dir", script: "mkdir -p ./bin"
                            container("utils") {
                                dir("bin") {
                                    sh label: "download third_party from OCI", script: """
                                        script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        \$script \
                                            --tidb=${OCI_TAG_TIDB} \
                                            --sync-diff-inspector=${OCI_TAG_SYNC_DIFF_INSPECTOR} \
                                            --minio=${OCI_TAG_MINIO}
                                    """
                                }
                            }
                            sh label: "download extra non-OCI tools", script: """
                                cd ./bin
                                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 \
                                    -O tidb-enterprise-tools-latest-linux-amd64.tar.gz \
                                    https://download.pingcap.com/tidb-enterprise-tools-latest-linux-amd64.tar.gz
                                tar -xzf tidb-enterprise-tools-latest-linux-amd64.tar.gz \
                                    tidb-enterprise-tools-latest-linux-amd64/bin/mydumper
                                mv tidb-enterprise-tools-latest-linux-amd64/bin/mydumper ./
                                rm -rf tidb-enterprise-tools-latest-linux-amd64 tidb-enterprise-tools-latest-linux-amd64.tar.gz

                                wget --no-verbose --retry-connrefused --waitretry=1 -t 3 \
                                    -O gh-ost.tar.gz \
                                    https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz
                                tar -xzf gh-ost.tar.gz
                                rm -f gh-ost.tar.gz
                                [ -f ./mydumper ] && chmod +x ./mydumper
                                [ -f ./gh-ost ] && chmod +x ./gh-ost
                                cd -
                                ls -alh ./bin
                                ./bin/tidb-server -V
                                ./bin/mydumper -V
                            """
                        }
                    }
                }
            }
        }
        stage("Test") {
            when { expression { !skipRemainingStages} }
            steps {
                dir('tiflow') {
                        sh label: "wait mysql ready", script: """
                            pwd && ls -alh
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                        """
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
