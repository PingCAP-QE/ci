// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/docs-tidb-operator'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/docs-tidb-operator/latest/pod-merged_update.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs


pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    options {
        timeout(time: 45, unit: 'MINUTES')
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
                dir("docs-tidb-operator") {
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
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir("docs-tidb-operator") {
                    withCredentials([
                        string(credentialsId: 'docs-cn-tencent-ak', variable: 'TENCENTCLOUD_RCLONE_CONN'),
                        string(credentialsId: 'docs-cn-tencent-bn', variable: 'TENCENTCLOUD_BUCKET_ID')
                    ]){
                        sh label: 'Build pdf', script: """#!/usr/bin/env bash
                            set -e
                            grep -RP '\t' *  | tee | grep '.md' && exit 1; echo ok
                            python3 scripts/merge_by_toc.py en/; python3 scripts/merge_by_toc.py zh/;
                            scripts/generate_pdf.sh
                        """
                        sh label: 'Upload pdf', script: """#!/usr/bin/env bash
                            set -e

                            dst="\${TENCENTCLOUD_RCLONE_CONN}:\${TENCENTCLOUD_BUCKET_ID}/pdf"
                            case "${REFS.base_ref}" in
                                "main")
                                    version="dev"
                                    ;;
                                "release-1.6")
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
                            # Upload TiDB Operator PDF to remote storage
                            rclone copyto output_en.pdf "\${dst}/tidb-in-kubernetes-\${version}-en-manual.pdf"
                            rclone copyto output_zh.pdf "\${dst}/tidb-in-kubernetes-\${version}-zh-manual.pdf"
                        """
                    }
                }
            }
        }
    }
}
