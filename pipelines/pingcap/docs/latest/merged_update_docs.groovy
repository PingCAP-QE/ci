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
            options { timeout(time: 45, unit: 'MINUTES') }

            steps {
                dir("docs") {
                    withCredentials([
                        string(credentialsId: 'docs-cn-aws-ak', variable: 'AWS_ACCESS_KEY'),
                        string(credentialsId: 'docs-cn-aws-sk', variable: 'AWS_SECRET_KEY'),
                        string(credentialsId: 'docs-cn-aws-region', variable: 'AWS_REGION'),
                        string(credentialsId: 'docs-cn-aws-bn', variable: 'AWS_BUCKET_NAME'),
                        string(credentialsId: 'docs-cn-qiniu-ak', variable: 'QINIU_ACCESS_KEY'),
                        string(credentialsId: 'docs-cn-qiniu-sk', variable: 'QINIU_SECRET_KEY'),
                        string(credentialsId: 'docs-cn-qiniu-bn', variable: 'QINIU_BUCKET_NAME')
                    ]){ 
                        // TODO: pre-install python3 packages(boto3, awscli) in the docker image
                        sh label: 'Build pdf', script: """#!/usr/bin/env bash
                            sudo pip3 install --trusted-host pypi.python.org --trusted-host pypi.org --trusted-host files.pythonhosted.org boto3 awscli
                            printf "%s\n" ${AWS_ACCESS_KEY} ${AWS_SECRET_KEY} ${AWS_REGION} "json" | aws configure
                            find -name '*.md' | xargs -d '\n' grep -P '\t' && exit 1
                            python3 scripts/merge_by_toc.py
                            scripts/generate_pdf.sh
                        """
                        sh label: 'Upload pdf', script: """#!/usr/bin/env bash
                            target_version=\$(echo ${REFS.base_ref} | sed 's/release-//')
                            if [ "${REFS.base_ref}" = "master" ]; then
                                python3 scripts/upload.py output.pdf tidb-dev-en-manual.pdf;
                            elif [ "${REFS.base_ref}" = "release-7.5" ]; then
                                python3 scripts/merge_by_toc.py TOC-tidb-cloud.md doc_cloud.md tidb-cloud; scripts/generate_cloud_pdf.sh;
                                python3 scripts/upload.py output_cloud.pdf tidbcloud-en-manual.pdf;
                                python3 scripts/upload.py output.pdf tidb-stable-en-manual.pdf;
                            elif case "${REFS.base_ref}" in release-*) ;; *) false;; esac; then
                                python3 scripts/upload.py output.pdf tidb-v\${target_version}-en-manual.pdf;
                            fi
                        """
                    }
                } 
            }
        }
    }
}
