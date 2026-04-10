// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for nextgen release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tiflow"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tiflow/latest/pod-pull_dm_integration_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_TIDB = component.computeArtifactNextGenOciTagFromPR('tidb', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactNextGenOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_PD = component.computeArtifactNextGenOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
// Keep sync-diff-inspector on the existing hub/community source until the
// nextgen tiflow package route is consumed by CI independently from this
// DM-only presubmit.
final OCI_TAG_SYNC_DIFF_INSPECTOR = 'master'
final OCI_TAG_MINIO = 'RELEASE.2020-02-27T00-23-05Z'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
        OCI_ARTIFACT_HOST_COMMUNITY = 'us-docker.pkg.dev/pingcap-testing-account/hub'
        NEXT_GEN = '1'
    }
    options {
        timeout(time: 120, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
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
            steps {
                dir("third_party_download") {
                    script {
                        retry(2) {
                            sh label: "prepare third_party dir", script: "mkdir -p bin"
                            container("utils") {
                                withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                                    sh label: "prepare docker auth", script: '''
                                        mkdir -p ~/.docker
                                        cp ${DOCKER_CONFIG_JSON} ~/.docker/config.json
                                    '''
                                }
                                dir("bin") {
                                    sh label: "download third_party from OCI", script: """
                                        script=${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh
                                        \$script \
                                            --tidb=${OCI_TAG_TIDB} \
                                            --tikv=${OCI_TAG_TIKV} \
                                            --pd=${OCI_TAG_PD} \
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
                }
                dir("tiflow") {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('binary', REFS, 'dm-integration-test-next-gen')) {
                        // build dm-master.test for integration test
                        // only build binarys if not exist, use the cached binarys if exist
                        // TODO: how to update cached binarys if needed
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
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm-next-gen") {
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
                        label "dm-it-${UUID.randomUUID().toString()}"
                        namespace K8S_NAMESPACE
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        defaultContainer 'golang'
                    }
                }
                stages {
                    stage("Test") {
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
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}/tiflow-dm-next-gen") {
                                    sh label: "wait mysql ready", script: """
                                        pwd && ls -alh
                                        # TODO use wait-for-mysql-ready.sh
                                        set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3306 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                        set +e && for i in {1..90}; do mysqladmin ping -h127.0.0.1 -P 3307 -p123456 -uroot --silent; if [ \$? -eq 0 ]; then set -e; break; else if [ \$i -eq 90 ]; then set -e; exit 2; fi; sleep 2; fi; done
                                    """
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
