// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final BRANCH_ALIAS = 'latest'
final POD_TEMPLATE_FILE = "pipelines/${GIT_FULL_REPO_NAME}/${BRANCH_ALIAS}/${JOB_BASE_NAME}/pod.yaml"
final REFS = readJSON(text: params.JOB_SPEC).refs

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            workspaceVolume genericEphemeralVolume(accessModes: 'ReadWriteOnce', requestsSize: '150Gi', storageClassName: 'hyperdisk-rwo')
            defaultContainer 'golang'
        }
    }
    environment {
        NEXT_GEN = '1'
    }
    options {
        timeout(time: 90, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    script {
                        prow.checkoutRefsWithCacheLock(REFS, timeout = 5, credentialsId = GIT_CREDENTIALS_ID, withSubmodule = true)
                    }
                }
            }
        }
        stage('Hotfix bazel deps/cache (temporary)') {
            steps {
                dir(REFS.repo) {
                    sh '''#!/usr/bin/env bash
                        set -euxo pipefail

                        # Clean legacy cache/mirror URLs that are unstable on GCP workers.
                        for f in WORKSPACE DEPS.bzl; do
                          [ -f "$f" ] || continue
                          sed -i -E '/bazel-cache[.]pingcap[.]net:8080|ats[.]apps[.]svc|cache[.]hawkingrei[.]com|mirror[.]bazel[.]build/d' "$f"
                        done

                        # Avoid "check" targets re-writing legacy cache settings during migration replay.
                        sed -i 's/^check: check-bazel-prepare /check: /' Makefile || true

                        # Ensure expected bazel tmp dir exists after mount point change.
                        mkdir -p /home/jenkins/.tidb/tmp
                    '''
                }
            }
        }
        stage("Build tidb-server community edition"){
            steps {
                dir(REFS.repo) {
                    sh "make bazel_build"
                }
            }
            post {
                always {
                    dir(REFS.repo) {
                        archiveArtifacts(artifacts: 'importer.log,tidb-server-check.log', allowEmptyArchive: true)
                    }
                }
            }
        }
        stage("Build tidb-server enterprise edition") {
            steps {
                dir(REFS.repo) {
                    sh "make enterprise-prepare enterprise-server-build && ./bin/tidb-server -V"
                    sh "./bin/tidb-server -V | grep -E '^Kernel Type:.*Next Generation'"
                }
            }
        }
        stage("Test plugin") {
            when { not { expression { REFS.base_ref ==~ /^feature[\/_].*/ } } } // skip for feature branches.
            steps {
                dir('enterprise-plugin') {
                    cache(path: "./", includes: '**/*', key: "git/pingcap-inc/enterprise-plugin/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap-inc/enterprise-plugin/rev-']) {
                        retry(2) {
                            script {
                                component.checkout('git@github.com:pingcap-inc/enterprise-plugin.git', 'plugin', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
                sh label: 'Test plugins', script: """
                    mkdir -p plugin-so

                    # compile go plugin: audit
                    pushd enterprise-plugin/audit && go mod tidy && popd
                    pushd ${REFS.repo} && go run ./cmd/pluginpkg -next-gen -pkg-dir ../enterprise-plugin/audit -out-dir ../plugin-so && popd

                    # compile go plugin: whitelist
                    pushd enterprise-plugin/whitelist && go mod tidy && popd
                    pushd ${REFS.repo} && go run ./cmd/pluginpkg -next-gen -pkg-dir ../enterprise-plugin/whitelist -out-dir ../plugin-so && popd

                    # test them.
                    make server -C ${REFS.repo}
                    ./${REFS.repo}/bin/tidb-server -keyspace-name=SYSTEM -plugin-dir=./plugin-so -plugin-load=audit-1,whitelist-1 | tee ./loading-plugin.log &

                    sleep 30
                    ps aux | grep tidb-server
                    killall -9 -r tidb-server
                """
            }
        }
    }
}
