// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final BRANCH_ALIAS = 'dedicated'
final REFS = readJSON(text: params.JOB_SPEC).refs
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = "${REFS.org}/${REFS.repo}"
final MAIN_POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/main-pod.yaml"
final TEST_POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/test-pod.yaml"

final OCI_TAG_PD = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final OCI_TAG_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master-next-gen")
final TARGET_BRANCH_TIDB = (REFS.base_ref ==~ /release-nextgen-.*/ ? REFS.base_ref : "master")
final MINIO_VERSION = 'RELEASE.2025-07-23T15-54-02Z'

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile MAIN_POD_TEMPLATE_FILE
        }
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
        // parallelsAlwaysFailFast() // disable for debug.
    }
    environment {
        NEXT_GEN = '1' // enable build and test for Next Gen kernel type.
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/tidbx'  // cache mirror for us-docker.pkg.dev/pingcap-testing-account/tidbx
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutPrivateRefs(REFS, GIT_CREDENTIALS_ID, timeout=5)
                            }
                        }
                    }
                }
                dir('tidb') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap/tidb.git', 'tidb', TARGET_BRANCH_TIDB, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                dir(REFS.repo) {
                    cache(path: "./bin", includes: '**/*', key: prow.getCacheKey('ng-binary', REFS)) {
                        container('builder') {
                            sh label: 'build tikv server and worker', script: '''#!/usr/bin/env bash
                                set -eo pipefail

                                if ls -l bin/{tikv-server,cse-ctl,tikv-worker} && [ $? -eq 0 ]; then
                                    echo "💾 Binary already exists (restored from cache)."
                                else
                                    echo "🚀 Binary not found. Proceeding with build."
                                    latest_devtoolset_dir=$(ls -d /opt/rh/devtoolset-* | sort -t- -k2,2nr | head -1)
                                    if [ -d "${latest_devtoolset_dir}" ]; then
                                        source ${latest_devtoolset_dir}/enable
                                    fi
                                    if [ "$(uname -m)" == "aarch64" ]; then
                                        export JEMALLOC_SYS_WITH_LG_PAGE=16
                                    fi

                                    make build
                                    mkdir bin
                                    mv -v target/debug/{tikv-server,cse-ctl,tikv-worker} bin/
                                fi

                                bin/tikv-server -V | grep -E "^Edition:.*Cloud Storage Engine"
                            '''
                        }
                    }
                }
                dir('tidb') {
                    container("utils") {
                        dir('bin') {
                            sh label: 'download peer component binaries', script: """#!/usr/bin/env bash
                                set -eo pipefail

                                script="\${WORKSPACE}/scripts/artifacts/download_pingcap_oci_artifact.sh"
                                chmod +x \$script
                                \${script} \
                                    --pd=${OCI_TAG_PD} \
                                    --tidb=${OCI_TAG_TIDB} \
                                    --minio=${MINIO_VERSION}
                            """
                            sh """#!/usr/bin/env bash
                                cp -v ${WORKSPACE}/${REFS.repo}/bin/{tikv-server,cse-ctl,tikv-worker} ./
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
                        yamlFile TEST_POD_TEMPLATE_FILE
                    }
                }
                when {
                    expression {
                        // Skip bazel_pushdowntest when base_ref is release-nextgen-20251011
                        return !(REFS.base_ref == 'release-nextgen-20251011' && "${SCRIPT_AND_ARGS}".contains(' bazel_pushdowntest'))
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
                                sed -i 's|repository_cache=/home/jenkins/.tidb/tmp|repository_cache=/share/.cache/bazel-repository-cache|g' Makefile.common
                                git diff .
                                git status
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
                                script {
                                    if ("$SCRIPT_AND_ARGS".contains(" bazel_")) {
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
                        }
                    }
                }
            }
        }
    }
}
