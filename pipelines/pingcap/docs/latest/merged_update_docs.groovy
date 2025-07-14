// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/docs'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/docs/latest/pod-merged_update_docs.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 100, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    python3 -V
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "branch ${REFS.base_ref}: ${REFS.base_sha}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("docs") {
                    container(name: 'node') {
                        sh label: "set git config", script: """
                        git config --global --add safe.directory '*'
                        """
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
        }
        stage('Build pdf') {
            options { timeout(time: 90, unit: 'MINUTES') }

            steps {
                dir("docs") {
                    withCredentials([
                        string(credentialsId: 'docs-cn-tencent-ak', variable: 'TENCENTCLOUD_RCLONE_CONN'),
                        string(credentialsId: 'docs-cn-tencent-bn', variable: 'TENCENTCLOUD_BUCKET_ID')
                    ]){
                        sh label: 'Build pdf', script: """#!/usr/bin/env bash
                            set -e
                            find -name '*.md' | xargs -d '\n' grep -P '\t' && exit 1
                            # Generate PDF for TiDB
                            python3 scripts/merge_by_toc.py
                            scripts/generate_pdf.sh
                            # Generate PDF for TiDB Cloud
                            if [ "${REFS.base_ref}" = "release-8.5" ]; then
                                python3 scripts/merge_by_toc.py TOC-tidb-cloud.md doc_cloud.md tidb-cloud
                                scripts/generate_cloud_pdf.sh
                            fi
                        """
                        sh label: 'Upload pdf', script: """#!/usr/bin/env bash
                            set -e

                            dst="\${TENCENTCLOUD_RCLONE_CONN}:\${TENCENTCLOUD_BUCKET_ID}/pdf"
                            case "${REFS.base_ref}" in
                                "master")
                                    version="dev"
                                    ;;
                                "release-8.5")
                                    version="stable"
                                    ;;
                                release-*)
                                    version="v\$(echo ${REFS.base_ref} | sed 's/release-//')"
                                    ;;
                                *)
                                    echo "Error: Unexpected base ref: ${REFS.base_ref}"
                                    exit 1
                                    ;;
                            esac
                            # Upload TiDB PDF to remote storage
                            rclone copyto output.pdf "\${dst}/tidb-\${version}-en-manual.pdf"
                            # Upload TiDB Cloud PDF to remote storage if it exists
                            if [ -f "output_cloud.pdf" ]; then
                                rclone copyto output_cloud.pdf "\${dst}/tidbcloud-en-manual.pdf"
                            fi
                        """
                    }
                }
            }
        }
    }
}
