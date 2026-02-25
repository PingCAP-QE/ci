// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-ghpr_check2.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')

prow.setPRDescription(REFS)
pipeline {
    agent none
    options {
        timeout(time: 65, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'  // cache mirror for us-docker.pkg.dev/pingcap-testing-account/hub
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yamlFile POD_TEMPLATE_FILE
                    defaultContainer 'golang'
                }
            }
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                    cache(path: "./bin", includes: 'tidb-server', key: prow.getCacheKey('binary', REFS)) {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || make server'
                    }
                    container("utils") {
                        dir("bin") {
                            script {
                                retry(2) {
                                    sh label: "download tidb components", script: """
                                        ${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh --pd=${OCI_TAG_PD} --tikv=${OCI_TAG_TIKV}
                                    """
                                }
                            }
                        }
                    }
                    // cache it for other pods
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh """
                            mv bin/tidb-server bin/integration_test_tidb-server
                            touch rev-${REFS.pulls[0].sha}
                        """
                    }
                }
            }
        }
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'integrationtest_with_tikv.sh y',
                            'integrationtest_with_tikv.sh n',
                            'run_real_tikv_tests.sh bazel_brietest',
                            'run_real_tikv_tests.sh bazel_pessimistictest',
                            'run_real_tikv_tests.sh bazel_sessiontest',
                            'run_real_tikv_tests.sh bazel_statisticstest',
                            'run_real_tikv_tests.sh bazel_txntest',
                            'run_real_tikv_tests.sh bazel_addindextest',
                            'run_real_tikv_tests.sh bazel_addindextest1',
                            'run_real_tikv_tests.sh bazel_addindextest2',
                            'run_real_tikv_tests.sh bazel_addindextest3',
                            'run_real_tikv_tests.sh bazel_addindextest4',
                            'run_real_tikv_tests.sh bazel_importintotest',
                            'run_real_tikv_tests.sh bazel_importintotest2',
                            'run_real_tikv_tests.sh bazel_importintotest3',
                            'run_real_tikv_tests.sh bazel_importintotest4',
                            'run_real_tikv_tests.sh bazel_pipelineddmltest',
                            'run_real_tikv_tests.sh bazel_flashbacktest',
                            'run_real_tikv_tests.sh bazel_ddltest',
                            'run_real_tikv_tests.sh bazel_pushdowntest',
                        )
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {
                    stage('Test')  {
                        environment {
                            CODECOV_TOKEN = credentials('codecov-token-tidb')
                        }
                        options { timeout(time: 50, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${REFS.pulls[0].sha}" // will fail when not found in cache or no cached.
                                }

                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh """
                                sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                                git diff .
                                git status
                                """
                                sh """
                                export BRIETEST_TMPDIR="${WORKSPACE}/tmp"
                                mkdir -vp "\$BRIETEST_TMPDIR"

                                ${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}
                                """
                            }
                        }
                        post {
                            always {
                                dir('tidb') {
                                    // archive test report to Jenkins.
                                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                                }
                            }
                            unsuccessful {
                                dir("tidb") {
                                    sh label: "archive log", script: """
                                    str="$SCRIPT_AND_ARGS"
                                    logs_dir="logs_\${str// /_}"
                                    mkdir -p \${logs_dir}
                                    mv pd*.log \${logs_dir} || true
                                    mv tikv*.log \${logs_dir} || true
                                    mv tests/integrationtest/integration-test.out \${logs_dir} || true
                                    tar -czvf \${logs_dir}.tar.gz \${logs_dir} || true
                                    """
                                    archiveArtifacts(artifacts: '*.tar.gz', allowEmptyArchive: true)
                                }
                            }
                            success {
                                dir("tidb") {
                                    script {
                                        prow.uploadCoverageToCodecov(REFS, 'integration', './coverage.dat')
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
