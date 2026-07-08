// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-6.5 branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-6.5/pod-pull_dm_integration_test.yaml'
final POD_TEMPLATE_FILE_BUILD = 'pipelines/pingcap/tiflow/release-6.5/pod-pull_dm_integration_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final HOTFIX_INFO = component.extractHotfixInfo(REFS.base_ref)
final OCI_TAG_TIDB = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, REFS.base_ref)
final OCI_TAG_TIKV = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_PD = HOTFIX_INFO.isHotfix ? HOTFIX_INFO.versionTag : component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'v6.5.12'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'
final WORKSPACE_STASH_NAME = 'tiflow-dm-workspace'
def skipRemainingStages = false

pipeline {
    agent none
    options {
        timeout(time: 60, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Check Diff & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE_BUILD, REFS)
                    retries 2
                    workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    defaultContainer 'golang'
                }
            }
            stages {
                stage('Check diff files') {
                    steps {
                        container("golang") {
                            script {
                                def pr_diff_files = component.getPrDiffFiles(GIT_FULL_REPO_NAME, REFS.pulls[0].number, GIT_CREDENTIALS_ID2)
                                def pattern = /(^dm\/|^pkg\/|^go\.mod).*$/
                                echo "pr_diff_files: ${pr_diff_files}"
                                // if any diff files start with dm/ or pkg/ or file go.mod, run the dm integration test
                                def matched = component.patternMatchAnyFile(pattern, pr_diff_files)
                                if (matched) {
                                    echo "matched, some diff files full path start with dm/ or pkg/ or go.mod, run the dm integration test"
                                } else {
                                    echo "not matched, all files full path not start with dm/ or pkg/ or go.mod, current pr not related to dm, so skip the dm integration test"
                                    currentBuild.result = 'SUCCESS'
                                    skipRemainingStages = true
                                    return 0 // exits script block; when guards on subsequent stages handle the skip
                                }
                            }
                        }
                    }
                }
                stage('Checkout') {
                    when { expression { !skipRemainingStages} }
                    options { timeout(time: 10, unit: 'MINUTES') }
                    steps {
                        dir(REFS.repo) {
                            script {
                                prow.checkoutRefsWithCacheLock(REFS)
                            }
                        }
                    }
                }
                stage("Prepare") {
                    when { expression { !skipRemainingStages} }
                    options { timeout(time: 20, unit: 'MINUTES') }
                    steps {
                        dir("third_party_download") {
                            retry(2) {
                                sh label: "prepare third_party dir", script: "mkdir -p bin"
                                container("utils") {
                                    dir("bin") {
                                        sh label: "download third_party from OCI", script: """
                                            script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                            \$script \
                                                --tidb=${OCI_TAG_TIDB} \
                                                --tikv=${OCI_TAG_TIKV} \
                                                --pd=${OCI_TAG_PD} \
                                                --pd-ctl=${OCI_TAG_PD} \
                                                --minio=${OCI_TAG_MINIO} \
                                                --sync-diff-inspector=${OCI_TAG_SYNC_DIFF_INSPECTOR}
                                        """
                                    }
                                }
                                sh label: "download gh-ost", script: """
                                    cd bin
                                    wget --no-verbose --retry-connrefused --waitretry=1 -t 3 \
                                        -O gh-ost.tar.gz \
                                        https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz
                                    tar -xzf gh-ost.tar.gz
                                    rm -f gh-ost.tar.gz
                                    [ -f ./gh-ost ] && chmod +x ./gh-ost
                                    ls -alh ./
                                    ./tidb-server -V
                                    ./pd-server -V
                                    ./tikv-server -V
                                """
                            }
                        }
                        dir(REFS.repo) {
                            cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'dm-integration-test')) {
                                // build dm-master.test for integration test
                                // only build binarys if not exist, use the cached binarys if exist
                                // TODO: how to update cached binarys if needed
                                sh label: "prepare", script: """
                                    if [[ ! -f "bin/dm-master.test" || ! -f "bin/dm-test-tools/check_master_online" || ! -f "bin/dm-test-tools/check_worker_online" ]]; then
                                        echo "Building binaries..."
                                        make dm_integration_test_build
                                        mkdir -p bin/dm-test-tools && cp -r ./dm/tests/bin/* ./bin/dm-test-tools
                                    else
                                        echo "Binaries already exist, skipping build..."
                                    fi
                                    ls -alh ./bin
                                    ls -alh ./bin/dm-test-tools
                                    which ./bin/dm-master.test
                                    which ./bin/dm-syncer.test
                                    which ./bin/dm-worker.test
                                    which ./bin/dmctl.test
                                    which ./bin/dm-test-tools/check_master_online
                                    which ./bin/dm-test-tools/check_worker_online
                                """
                            }
                            sh label: "prepare workspace", script: """
                                cp -r ../third_party_download/bin/* ./bin/
                                ls -alh ./bin
                                ls -alh ./bin/dm-test-tools
                            """
                            stash includes: '**/*', name: WORKSPACE_STASH_NAME, useDefaultExcludes: false
                        }
                    }
                }
            }
        }

        stage('Tests') {
            when { expression { !skipRemainingStages} }
            matrix {
                axes {
                    axis {
                        name 'TEST_GROUP'
                        values 'G00', 'G01', 'G02', 'G03', 'G04', 'G05', 'G06', 'G07', 'G08',
                            'G09', 'G10', 'G11', 'G12', 'G13', 'TLS_GROUP'
                    }
                }
                agent{
                    kubernetes {
                        label "dm-it-${UUID.randomUUID().toString()}"
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        retries 2
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                        defaultContainer 'golang'
                    }
                }
                when {
                    beforeAgent true
                    expression { return !matrixCache.shouldSkip(REFS, 'Test', [test_group: env.TEST_GROUP]) }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 40, unit: 'MINUTES') }
                        environment {
                            DM_CODECOV_TOKEN = credentials('codecov-token-tiflow')
                            DM_COVERALLS_TOKEN = credentials('coveralls-token-tiflow')
                        }
                        steps {
                            container("mysql1") {
                                sh label: "copy mysql certs", script: """
                                    mkdir ${WORKSPACE}/mysql-ssl
                                    cp -r /var/lib/mysql/*.pem ${WORKSPACE}/mysql-ssl/
                                    ls -alh ${WORKSPACE}/mysql-ssl/
                                """
                            }

                            dir(REFS.repo) {
                                unstash name: WORKSPACE_STASH_NAME
                                timeout(time: 10, unit: 'MINUTES') {
                                    sh label: "wait mysql ready", script: """
                                        pwd && ls -alh
                                        # TODO use wait-for-mysql-ready.sh
                                        set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                        set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                    """
                                }
                                sh label: "${TEST_GROUP}", script: """
                                    chmod +x ../scripts/pingcap/tiflow/release-6.5/dm_run_group.sh
                                    cp ../scripts/pingcap/tiflow/release-6.5/dm_run_group.sh dm/tests/

                                    if [ "TLS_GROUP" == "${TEST_GROUP}" ] ; then
                                        echo "run tls test"
                                        echo "copy mysql certs"
                                        sudo mkdir -p /var/lib/mysql
                                        sudo chmod 777 /var/lib/mysql
                                        sudo chown -R 1000:1000 /var/lib/mysql
                                        sudo cp -r ${WORKSPACE}/mysql-ssl/*.pem /var/lib/mysql/
                                        sudo chown -R 1000:1000 /var/lib/mysql/*
                                        ls -alh /var/lib/mysql/
                                    else
                                        echo "run ${TEST_GROUP} test"
                                    fi
                                    export PATH=/usr/local/go/bin:\$PATH
                                    mkdir -p ./dm/tests/bin && cp -r ./bin/dm-test-tools/* ./dm/tests/bin/
                                    echo "install python requirments for test"
                                    pip install --user -q -r ./dm/tests/requirements.txt
                                    cd dm && ln -sf ../bin . && cd ..
                                    export PATH=${WORKSPACE}/tiflow/dm/bin:\$PATH
                                    cd dm && ./tests/dm_run_group.sh "${TEST_GROUP}"
                                """
                            }
                        }
                        post {
                            failure {
                                sh label: "collect logs", script: """
                                    ls /tmp/dm_test
                                    tar -cvzf log-${TEST_GROUP}.tar.gz \$(find /tmp/dm_test/ -type f -name "*.log")
                                    ls -alh  log-${TEST_GROUP}.tar.gz
                                """
                                archiveArtifacts artifacts: "log-${TEST_GROUP}.tar.gz", allowEmptyArchive: true
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [test_group: env.TEST_GROUP]) } }
                        }
                    }
                }
            }
        }
    }
}
