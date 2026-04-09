// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_integration_test_next_gen.yaml'
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
                        def pattern = /(^dm\/|^pkg\/|^sync_diff_inspector\/|^go\.mod).*$/
                        println "pr_diff_files: ${pr_diff_files}"
                        // if any diff files start with dm/ or pkg/ or file go.mod, run the dm integration test
                        // besides, any changes in sync_diff_inspector also need to run test
                        def matched = component.patternMatchAnyFile(pattern, pr_diff_files)
                        if (matched) {
                            println "matched, some diff files full path start with dm/, sync_diff_inspector/ or pkg/ or go.mod, run the dm integration test"
                        } else {
                            println "not matched, all files full path not start with dm/, sync_diff_inspector/ or pkg/ or go.mod, current pr not releate to dm, so skip the dm integration test"
                            currentBuild.result = 'SUCCESS'
                            skipRemainingStages = true
                            return 0
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
            options { timeout(time: 20, unit: 'MINUTES') }
            steps {
                dir("third_party_download") {
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download next-gen tidb components", script: """
                                        export script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        chmod +x \$script
                                        \$script \
                                            --pd=${OCI_TAG_PD} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --tidb=${OCI_TAG_TIDB} \
                                            --minio=RELEASE.2025-07-23T15-54-02Z
                                        ls -alh
                                    """
                                }
                            }
                        }
                    }
                    sh label: "verify binaries", script: """
                        ls -alh ./bin
                        ./bin/tidb-server -V
                        ./bin/pd-server -V
                        ./bin/tikv-server -V
                    """
                    // download gh-ost
                    sh label: "download gh-ost", script: """
                        wget --no-verbose --retry-connrefused --waitretry=1 -t 3 -O ./bin/gh-ost.tar.gz https://github.com/github/gh-ost/releases/download/v1.1.0/gh-ost-binary-linux-20200828140552.tar.gz
                        tar -xz -C ./bin -f ./bin/gh-ost.tar.gz
                        rm -f ./bin/gh-ost.tar.gz
                        chmod +x ./bin/gh-ost
                    """
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'dm-integration-test-ng')) {
                        // build dm-master.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        sh label: "prepare", script: """
                            if [[ ! -f "bin/sync_diff_inspector" || ! -f "bin/dm-master.test" || ! -f "bin/dm-test-tools/check_master_online" || ! -f "bin/dm-test-tools/check_worker_online" ]]; then
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
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm-ng") {
                        sh label: "prepare", script: """
                            cp -r ../third_party_download/bin/* ./bin/
                            ls -alh ./bin
                            ls -alh ./bin/dm-test-tools
                        """
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
                            'G09', 'G10', 'G11', 'TLS_GROUP'
                    }
                }
                agent{
                    kubernetes {
                        label "dm-it-ng-${UUID.randomUUID().toString()}"
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
                        options { timeout(time: 50, unit: 'MINUTES') }
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

                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm-ng") {
                                    timeout(time: 10, unit: 'MINUTES') {
                                        sh label: "wait mysql ready", script: """
                                            pwd && ls -alh
                                            # TODO use wait-for-mysql-ready.sh
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                        """
                                    }
                                    sh label: "${TEST_GROUP}", script: """
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
                                        make dm_integration_test_in_group GROUP="${TEST_GROUP}"
                                    """
                                }
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
                        }
                    }
                }
            }
        }
    }
}
