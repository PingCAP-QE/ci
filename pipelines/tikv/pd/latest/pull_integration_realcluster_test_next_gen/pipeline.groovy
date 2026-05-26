// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final BRANCH_ALIAS = 'latest'
final K8S_NAMESPACE = "jenkins-pd"
final GIT_CREDENTIALS_ID = ''
final REFS = readJSON(text: params.JOB_SPEC).refs
final GIT_FULL_REPO_NAME = "${REFS.org}/${REFS.repo}"
final MAIN_POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/main-pod.yaml"
final TEST_POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/test-pod.yaml"

final OCI_TAG_TIFLASH = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-nextgen")
final OCI_TAG_TIKV = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "cloud-engine-nextgen")
final TARGET_BRANCH_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master")
final MINIO_VERSION = 'RELEASE.2025-07-23T15-54-02Z'

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(MAIN_POD_TEMPLATE_FILE, REFS)
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'builder'
        }
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        // parallelsAlwaysFailFast() // disable for debug.
    }
    environment {
        NEXT_GEN = '1' // enable build and test for Next Gen kernel type.
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/tidbx'
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, 5, GIT_CREDENTIALS_ID)
                    }
                }
                dir('tidb') {
                    retry(2) {
                        script {
                            component.checkout('https://github.com/pingcap/tidb.git', 'tidb', TARGET_BRANCH_TIDB, REFS.pulls[0].title, '')
                        }
                    }
                }
            }
        }
        stage('Hotfix bazel deps/cache (temporary)') {
            steps {
                dir('tidb') {
                    sh '''#!/usr/bin/env bash
                        set -euxo pipefail

                        # Clean legacy cache/mirror URLs that are unstable on GCP workers.
                        for f in WORKSPACE DEPS.bzl; do
                          [ -f "$f" ] || continue
                          sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$f"
                        done

                        # Avoid "check" targets re-writing legacy cache settings during migration replay.
                        sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true

                        # Disable remote cache usage for this migration replay path.
                        if [ -f .bazelrc ]; then
                          sed -i '/^try-import \\/data\\/bazel$/d' .bazelrc
                          grep -q '^build --noremote_accept_cached$' .bazelrc || echo 'build --noremote_accept_cached' >> .bazelrc
                          grep -q '^build --noremote_upload_local_results$' .bazelrc || echo 'build --noremote_upload_local_results' >> .bazelrc
                          grep -q '^test --noremote_accept_cached$' .bazelrc || echo 'test --noremote_accept_cached' >> .bazelrc
                          grep -q '^test --noremote_upload_local_results$' .bazelrc || echo 'test --noremote_upload_local_results' >> .bazelrc
                          grep -q '^run --noremote_accept_cached$' .bazelrc || echo 'run --noremote_accept_cached' >> .bazelrc
                          grep -q '^run --noremote_upload_local_results$' .bazelrc || echo 'run --noremote_upload_local_results' >> .bazelrc
                        fi
                    '''
                }
            }
        }
        stage('Prepare') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('ng-binary', REFS)) {
                        container('builder') {
                            sh label: 'pd-server', script: '[ -f bin/pd-server ] || WITH_RACE=1 RUN_CI=1 make pd-server-basic'
                            sh 'bin/pd-server -V'
                        }
                    }
                }
                dir('tidb') {
                    container("utils") {
                        withCredentials([file(credentialsId: 'tidbx-docker-config', variable: 'DOCKER_CONFIG_JSON')]) {
                            sh label: "prepare docker auth", script: '''
                                mkdir -p ~/.docker
                                cp ${DOCKER_CONFIG_JSON} ~/.docker/config.json
                            '''
                        }
                        dir('bin') {
                            sh label: 'download peer component binaries', script: """#!/usr/bin/env bash
                                set -eo pipefail

                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} \
                                    --tidb=${OCI_TAG_TIDB} \
                                    --tikv=${OCI_TAG_TIKV} \
                                    --tikv-worker=${OCI_TAG_TIKV} \
                                    --tiflash=${OCI_TAG_TIFLASH} \
                                    --minio=${MINIO_VERSION}
                            """
                            sh """#!/usr/bin/env bash
                                cp -v ${WORKSPACE}/${REFS.repo}/bin/pd-server ./
                            """
                        }
                    }
                    // cache it for other pods
                    cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                        sh "touch rev-${REFS.pulls[0].sha}"
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
                            'tests/integrationtest/run-tests-next-gen.sh -s bin/tidb-server -d n',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_sessiontest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_statisticstest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_addindextest1',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_addindextest2',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_addindextest3',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_addindextest4',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_importintotest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_importintotest2',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_importintotest3',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_importintotest4',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_addindextest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_pipelineddmltest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_pessimistictest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_txntest',
                            'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_pushdowntest',
                            // 🚧 Failed or timeouted groups:
                            // 'tests/integrationtest/run-tests-next-gen.sh -s bin/tidb-server -d y',
                            // 'tests/realtikvtest/scripts/next-gen/run-tests.sh bazel_ddltest',
                        )
                    }
                }
                agent {
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yaml pod_label.withCiLabels(TEST_POD_TEMPLATE_FILE, REFS)
                        workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '200Gi', storageClassName: 'hyperdisk-rwo')
                    }
                }
                when {
                    beforeAgent true
                    allOf {
                        expression {
                            // Skip bazel_pushdowntest when base_ref is release-nextgen-20251011
                            return !(REFS.base_ref == 'release-nextgen-20251011' && env.SCRIPT_AND_ARGS.contains(' bazel_pushdowntest'))
                        }
                        expression { return !matrixCache.shouldSkip(REFS, 'Test', [script_and_args: env.SCRIPT_AND_ARGS]) }
                    }
                }
                stages {
                    stage('Test')  {
                        environment {
                            MINIO_BIN_PATH = "bin/minio"
                        }
                        steps {
                            dir('tidb') {
                                cache(path: "./", includes: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh "ls -l rev-${REFS.pulls[0].sha}" // will fail when not found in cache or no cached.
                                }

                                sh """
                                mkdir -p /home/jenkins/.tidb/tmp
                                git diff . || true
                                git status || true
                                """
                                sh """#! /usr/bin/env bash
                                    set -o pipefail

                                    $SCRIPT_AND_ARGS 2>&1 | tee bazel-test.log
                                """
                            }
                        }
                        post {
                            always {
                                dir('tidb') {
                                    // archive test report to Jenkins.
                                    junit(testResults: "**/bazel.xml", allowEmptyResults: true)
                                }
                                script {
                                    if ("$SCRIPT_AND_ARGS".contains(" bazel_")) {
                                        sh label: "Parse flaky test case results", script: './scripts/plugins/analyze-go-test-from-bazel-output.sh tidb/bazel-test.log || true'
                                        prow.sendTestCaseRunReport("pingcap/tidb", "${TARGET_BRANCH_TIDB}")
                                        sh """
                                            logs_dir="logs_\$(echo \"\$SCRIPT_AND_ARGS\" | tr ' /' '_')"
                                            mkdir -p \$logs_dir
                                            mv tidb/bazel-test.log \$logs_dir 2>/dev/null || true
                                            mv bazel-*.log \$logs_dir 2>/dev/null || true
                                            mv bazel-*.json \$logs_dir 2>/dev/null || true
                                        """
                                        archiveArtifacts(artifacts: '*/bazel-*.log,*/bazel-*.json', fingerprint: false, allowEmptyArchive: true)
                                    }
                                }
                            }
                            unsuccessful {
                                dir('tidb') {
                                    sh label: "archive log", script: """
                                    str="$SCRIPT_AND_ARGS"
                                    logs_dir="logs_\$(echo \"\$str\" | tr ' /' '_')"
                                    mkdir -p "\${logs_dir}"
                                    mv pd*.log "\${logs_dir}" || true
                                    mv tikv*.log "\${logs_dir}" || true
                                    tar -czvf "\${logs_dir}.tar.gz" "\${logs_dir}" || true
                                    """
                                    archiveArtifacts(artifacts: '*.tar.gz', allowEmptyArchive: true)
                                }
                            }
                            success { script { matrixCache.markDone(REFS, 'Test', [script_and_args: env.SCRIPT_AND_ARGS]) } }
                        }
                    }
                }
            }
        }
    }
}
