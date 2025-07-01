// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_FULL_REPO_NAME = 'pingcap/tiflow'
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_CREDENTIALS_ID2 = 'github-pr-diff-token'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/release-6.5-fips/pod-pull_dm_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
def skipRemainingStages = false

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
        timeout(time: 60, unit: 'MINUTES')
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
                        // if any diff files start with dm/ or pkg/ or file go.mod, run the dm integration test
                        def matched = component.patternMatchAnyFile(pattern, pr_diff_files)
                        if (matched) {
                            println "matched, some diff files full path start with dm/ or pkg/ or go.mod, run the dm integration test"
                        } else {
                            println "not matched, all files full path not start with dm/ or pkg/ or go.mod, current pr not releate to dm, so skip the dm integration test"
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
                    retry(2) {
                        sh label: "download third_party", script: """
                            chmod +x ../scripts/pingcap/tiflow/release-6.5-fips/dm_download_integration_test_binaries.sh
                            cp ../scripts/pingcap/tiflow/release-6.5-fips/dm_download_integration_test_binaries.sh ../tiflow/dm/tests/

                            cd ../tiflow && ./dm/tests/dm_download_integration_test_binaries.sh && ls -alh ./bin
                            cd - && mkdir -p bin && mv ../tiflow/bin/* ./bin/
                            ls -alh ./bin
                            ./bin/tidb-server -V
                            ./bin/pd-server -V
                            ./bin/tikv-server -V
                        """
                    }
                }
                dir("tiflow") {
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
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") {
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
                            'G09', 'G10', 'G11', 'G12', 'G13', 'TLS_GROUP'
                    }
                }
                agent{
                    kubernetes {
                        label "dm-it-${UUID.randomUUID().toString()}"
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                    }
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

                            dir('tiflow') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm") {
                                    timeout(time: 10, unit: 'MINUTES') {
                                        sh label: "wait mysql ready", script: """
                                            pwd && ls -alh
                                            # TODO use wait-for-mysql-ready.sh
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                            set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                        """
                                    }
                                    sh label: "${TEST_GROUP}", script: """
                                        chmod +x ../scripts/pingcap/tiflow/release-6.5-fips/dm_run_group.sh
                                        cp ../scripts/pingcap/tiflow/release-6.5-fips/dm_run_group.sh dm/tests/

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
