// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-8.5/pod-pull_check2.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'

prow.setPRDescription(REFS)
pipeline {
    agent none
    options {
        timeout(time: 65, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    stages {
        stage('Checkout & Prepare') {
            agent {
                kubernetes {
                    namespace K8S_NAMESPACE
                    yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                    workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    defaultContainer 'golang'
                }
            }
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID)
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

                    sh '''#!/usr/bin/env bash
                    set -euxo pipefail
                    for f in WORKSPACE DEPS.bzl; do
                      [ -f "$f" ] || continue
                      sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$f"
                    done

                    # Keep replay and job behavior aligned until tidb repo deps URLs are cleaned up.
                    sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true

                    if [ -f .bazelrc ]; then
                      [ -n "$(tail -c1 .bazelrc 2>/dev/null)" ] && echo "" >> .bazelrc
                      for cmd in build test run; do
                        grep -q "^${cmd} --noremote_upload_local_results$" .bazelrc || echo "${cmd} --noremote_upload_local_results" >> .bazelrc
                      done
                    fi

                    grep -nE 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build' WORKSPACE DEPS.bzl || true
                    grep -n '^check:' Makefile | head -n 3 || true
                    grep -n 'noremote_upload_local_results' .bazelrc | tail -n 6 || true
                    '''

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
                            'run_real_tikv_tests.sh bazel_pushdowntest',
                        )
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
                    }
                }
                stages {
                    stage('Test')  {
                        environment {
                            CODECOV_TOKEN = credentials('codecov-token-tidb')
                        }
                        options { timeout(time: 50, unit: 'MINUTES') }
                        steps {
                            dir(REFS.repo) {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${REFS.pulls[0].sha}" // will fail when not found in cache or no cached.
                                }

                                // Lightweight fallback: only re-apply when stale URLs are still present in restored cache.
                                sh '''#!/usr/bin/env bash
                                    set -euxo pipefail
                                    if grep -qE 'bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build' WORKSPACE DEPS.bzl 2>/dev/null; then
                                        for f in WORKSPACE DEPS.bzl; do
                                          [ -f "$f" ] || continue
                                          sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$f"
                                        done
                                        sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true
                                    fi

                                    if [ -f .bazelrc ]; then
                                        [ -n "$(tail -c1 .bazelrc 2>/dev/null)" ] && echo "" >> .bazelrc
                                        for cmd in build test run; do
                                          grep -q "^${cmd} --noremote_upload_local_results$" .bazelrc || echo "${cmd} --noremote_upload_local_results" >> .bazelrc
                                        done
                                    fi
                                '''

                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh """
                                git diff .
                                git status
                                """
                                sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                            }
                        }
                        post {
                            always {
                                dir(REFS.repo) {
                                    // archive test report to Jenkins.
                                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                                }
                            }
                            unsuccessful {
                                dir(REFS.repo) {
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
                                dir(REFS.repo) {
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
