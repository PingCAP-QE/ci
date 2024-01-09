// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/docs-cn'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/docs-cn/latest/pod-pull_verify.yaml'
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
                    go env
                    echo "-------------------------"
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 10, unit: 'MINUTES') }
            steps {
                dir("docs-cn") {
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
        stage('Tests') { 
            stages {
                // TODO: check whether the agent need configured in each stage
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'runner'
                    }
                }
                parallel {
                    stage('check file encoding') {
                        steps {
                            dir('docs-cn') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-file-encoding.py
                                    python3 check-file-encoding.py \$(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*')
                                    """
                                }
                            }
                        }
                    }
                    stage('check git conflicts') {
                        steps {
                            dir('docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-conflicts.py
                                    python3 check-conflicts.py \$(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' '*.yml' '*.yaml')
                                    """
                                }
                            }
                        }
                    }
                    stage('Lint edited files') {
                        steps {
                            dir('docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    markdownlint $(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*')
                                    """
                                }
                            }
                        }
                    }
                    stage('Check control characters') {
                        steps {
                            dir('docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-control-char.py
                                    python3 check-control-char.py \$(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*'
                                    """
                                }
                            }
                        }
                    }
                    stage('Check unclosed tags') {
                        steps {
                            dir('docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-tags.py
                                    python3 check-tags.py \$(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*')
                                    """
                                }
                            }
                        }
                    }
                    stage('Check manual line breaks') {
                        steps {
                            dir('docs') {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) { 
                                    sh """
                                    git config --global --add safe.directory '*'
                                    # TODO: check whether the git config is needed
                                    git remote add upstream https://github.com/pingcap/docs-cn.git
                                    git fetch upstream
                                    """
                                    sh """#!/usr/bin/env bash
                                    wget https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-manual-line-breaks.py
                                    python3 check-manual-line-breaks.py \$(git diff-tree --name-only --no-commit-id -r upstream/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*')
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
