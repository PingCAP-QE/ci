// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/docs'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/docs/latest/pod-pull_verify.yaml'
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
        timeout(time: 40, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv
                    echo "-------------------------"
                    node -v
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                    script {
                        currentBuild.description = "PR #${REFS.pulls[0].number}: ${REFS.pulls[0].title} ${REFS.pulls[0].link}"
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("git-docs") {
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
        stage('Prepare') {
            steps {
                dir('check-scripts') {
                    cache(path: "./", includes: '**/*', key: "ws/check-scripts/${BUILD_TAG}") {
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
        }

        stage('Tests') {
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
                    stage("Test") {
                        options { timeout(time: 20, unit: 'MINUTES') }
                        steps {
                            dir('check-scripts') {
                                cache(path: "./", includes: '**/*', key: "ws/check-scripts/${BUILD_TAG}") {
                                    sh """
                                    ls -alh
                                    """
                                }
                            }
                            dir('git-docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    script {
                                        def currentDir = pwd()
                                        def gitRepoRoot = sh(
                                            script: 'git rev-parse --show-toplevel 2>/dev/null || echo ""',
                                            returnStdout: true
                                        ).trim()
                                        
                                        if (!gitRepoRoot || gitRepoRoot != currentDir) {
                                            echo "Git repository invalid or root mismatch, re-checking out..."
                                            sh 'rm -rf .git'
                                            sh 'git config --global --add safe.directory "*"'
                                            prow.checkoutRefs(REFS, 10)
                                        }
                                    }
                                    sh label: "set git config", script: """
                                    git config --global --add safe.directory '*'
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
}
