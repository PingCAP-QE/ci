// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/docs'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/docs/latest/pod-pull_verify.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent none
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage("Tests") {
            matrix {
                axes {
                    axis {
                        name 'CHECK_CMD'
                        values "python3 check-file-encoding.py", "python3 check-conflicts.py", "markdownlint",
                            "python3 check-control-char.py", "python3 check-tags.py", "python3 check-manual-line-breaks.py"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'runner'
                    }
                }
                stages {
                    stage('Checkout') {
                        steps {
                            dir(REFS.repo) {
                                sh label: "set git config", script: """
                                git config --global --add safe.directory '*'
                                """
                                script {
                                    prow.checkoutRefsWithCache(REFS)
                                }
                            }
                        }
                    }
                    stage('Prepare') {
                        steps {
                            dir('check-scripts') {
                                sh label: 'Prepare', script: """
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-file-encoding.py
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-conflicts.py
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-control-char.py
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-tags.py
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-manual-line-breaks.py
                                """
                            }
                        }
                    }
                    stage("Test") {
                        steps {
                            dir(REFS.repo) {
                                sh label: "set git config", script: """
                                git rev-parse --show-toplevel
                                git status -s .
                                git log --format="%h %B" --oneline -n 3
                                """
                                // TODO: remove this debug lines
                                sh """
                                git diff-tree --name-only --no-commit-id -r origin/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*'
                                """
                                sh label: "check ${CHECK_CMD}", script: """#!/usr/bin/env bash
                                cp -r ../check-scripts/* ./
                                diff_docs_files=\$(git diff-tree --name-only --no-commit-id -r origin/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*')

                                if [[ "${CHECK_CMD}" == "markdownlint" ]]; then
                                    npm install -g markdownlint-cli@0.17.0
                                fi

                                ${CHECK_CMD} \$diff_docs_files
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
