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
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'runner'
        }
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    sh label: 'set git config', script: """
                    git config --global --add safe.directory '*'
                    """
                    script {
                        prow.checkoutRefsWithCacheLock(REFS)
                    }
                }
            }
        }
        stage('Prepare') {
            steps {
                dir(REFS.repo) {
                    sh label: 'Prepare check scripts', script: """#!/usr/bin/env bash
                    set -euo pipefail

                    rm -rf ../check-scripts
                    mkdir -p ../check-scripts

                    wget -O ../check-scripts/check-file-encoding.py https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-file-encoding.py
                    wget -O ../check-scripts/check-conflicts.py https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-conflicts.py
                    wget -O ../check-scripts/check-control-char.py https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-control-char.py
                    wget -O ../check-scripts/check-tags.py https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-tags.py
                    wget -O ../check-scripts/check-manual-line-breaks.py https://raw.githubusercontent.com/pingcap/docs/master/scripts/check-manual-line-breaks.py

                    ls -al ../check-scripts
                    """
                }
            }
        }
        stage('Verify') {
            steps {
                dir(REFS.repo) {
                    sh label: 'Debug info', script: """
                    git rev-parse --show-toplevel
                    git status -s .
                    git log --format="%h %B" --oneline -n 3
                    """
                    sh label: 'Run pull_verify checks', script: """#!/usr/bin/env bash
                    set -uo pipefail

                    mapfile -t diff_docs_files < <(
                        git diff-tree --name-only --no-commit-id -r origin/${REFS.base_ref}..HEAD -- '*.md' ':(exclude).github/*'
                    )

                    if ((\${#diff_docs_files[@]} == 0)); then
                        echo 'No changed Markdown files, skip pull_verify checks.'
                        exit 0
                    fi

                    printf 'Changed Markdown files (%s):\\n' "\${#diff_docs_files[@]}"
                    printf ' - %s\\n' "\${diff_docs_files[@]}"

                    cp -r ../check-scripts/* ./

                    failed_checks=()

                    run_check() {
                        local name="\$1"
                        shift

                        echo "==> Running \${name}"
                        if "\$@" "\${diff_docs_files[@]}"; then
                            echo "==> \${name}: PASS"
                        else
                            echo "==> \${name}: FAIL"
                            failed_checks+=("\${name}")
                        fi
                        echo
                    }

                    run_check 'check-file-encoding' python3 ./check-file-encoding.py
                    run_check 'check-conflicts' python3 ./check-conflicts.py
                    run_check 'check-control-char' python3 ./check-control-char.py
                    run_check 'check-tags' python3 ./check-tags.py
                    run_check 'check-manual-line-breaks' python3 ./check-manual-line-breaks.py

                    echo '==> Installing markdownlint-cli@0.17.0'
                    if npm install -g markdownlint-cli@0.17.0; then
                        run_check 'markdownlint' markdownlint
                    else
                        echo '==> markdownlint-install: FAIL'
                        failed_checks+=('markdownlint-install')
                    fi

                    if ((\${#failed_checks[@]} > 0)); then
                        printf 'pull_verify failed checks (%s):\\n' "\${#failed_checks[@]}"
                        printf ' - %s\\n' "\${failed_checks[@]}"
                        exit 1
                    fi

                    echo 'All pull_verify checks passed.'
                    """
                }
            }
        }
    }
}
