// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-pull_integration_e2e_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs
final OCI_TAG_PD = component.computeArtifactOciTagFromPR('pd', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TICDC_NEW = component.computeArtifactOciTagFromPR('ticdc', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TIDB = component.computeArtifactOciTagFromPR('tidb', (REFS.base_ref ==~ /^release-fts-[0-9]+$/ ? 'master' : REFS.base_ref), REFS.pulls[0].title, 'master')
final OCI_TAG_TIKV = component.computeArtifactOciTagFromPR('tikv', REFS.base_ref, REFS.pulls[0].title, 'master')
final OCI_TAG_TIFLASH = component.computeArtifactOciTagFromPR('tiflash', REFS.base_ref, REFS.pulls[0].title, 'master')
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'

prow.setPRDescription(REFS)
pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yaml pod_label.withCiLabels(POD_TEMPLATE_FILE, REFS)
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'us-docker.pkg.dev/pingcap-testing-account/hub'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
    }
    stages {
        stage('Checkout') {
            steps {
                dir(REFS.repo) {
                    cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS), restoreKeys: prow.getRestoreKeys('git', REFS)) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS, credentialsId = GIT_CREDENTIALS_ID)
                            }
                        }
                    }
                }
            }
        }
        stage('Tests') {
            options { timeout(time: 45, unit: 'MINUTES') }
            steps {
                dir("${REFS.repo}/tests/integrationtest2") {
                    dir("third_bin") {
                        container("utils") {
                            script {
                                retry(2) {
                                    withEnv([
                                        "TAG_DUMPLING=${OCI_TAG_TIDB}",
                                        "TAG_BR=${OCI_TAG_TIKV}",
                                        "TAG_PD=${OCI_TAG_PD}",
                                        "TAG_TIKV=${OCI_TAG_TIKV}",
                                        "TAG_TIFLASH=${OCI_TAG_TIFLASH}",
                                        "TAG_TICDC_NEW=${OCI_TAG_TICDC_NEW}",
                                    ]) {
                                        sh label: "download tidb components", script: '''#!/usr/bin/env bash
                                            set -euxo pipefail
                                            script_path="$WORKSPACE/scripts/artifacts/download_pingcap_oci_artifact.sh"

                                            if grep -q -- '--br=' "$script_path"; then
                                                "$script_path" \
                                                    --dumpling="$TAG_DUMPLING" \
                                                    --br="$TAG_BR" \
                                                    --pd="$TAG_PD" \
                                                    --tikv="$TAG_TIKV" \
                                                    --tiflash="$TAG_TIFLASH" \
                                                    --ticdc-new="$TAG_TICDC_NEW"
                                            else
                                                "$script_path" \
                                                    --dumpling="$TAG_DUMPLING" \
                                                    --pd="$TAG_PD" \
                                                    --tikv="$TAG_TIKV" \
                                                    --tiflash="$TAG_TIFLASH" \
                                                    --ticdc-new="$TAG_TICDC_NEW"

                                                if [[ ! -x ./br ]]; then
                                                    br_oci_url="$OCI_ARTIFACT_HOST/pingcap/tidb/package:$TAG_BR"_linux_amd64
                                                    repo=$(echo "$br_oci_url" | cut -d ':' -f 1)
                                                    echo "🚀 start download BR from $br_oci_url"
                                                    oras manifest fetch "$br_oci_url" | yq --prettyPrint -oy '.layers | filter(.annotations["org.opencontainers.image.title"] | test "^br-v.+.tar.gz$") | .[0]' > br_blob.yaml
                                                    br_file=$(yq '.annotations["org.opencontainers.image.title"]' br_blob.yaml)
                                                    br_blob="$repo@$(yq '.digest' br_blob.yaml)"
                                                    echo "🔗 blob fetching url: $br_blob"
                                                    oras blob fetch --output "$br_file" "$br_blob"
                                                    mv -v "$br_file" br.tar.gz
                                                    tar -xzvf br.tar.gz br
                                                    rm -f br.tar.gz br_blob.yaml
                                                    chmod +x br
                                                    echo "🎉 download BR success"
                                                fi
                                            fi
                                        '''
                                    }
                                }
                            }
                        }
                        sh '''
                            ./br -V
                            ./dumpling --version || ./dumpling -V || true
                            ./tikv-server -V
                            ./pd-server -V
                            ./tiflash --version
                        '''
                    }
                    sh label: 'test', script: '''#!/usr/bin/env bash
                        set -euxo pipefail
                        pushd ../..
                        make server
                        popd
                        ./run-tests.sh -s ../../bin/tidb-server
                    '''
                }
            }
            post{
                failure {
                    archiveArtifacts(artifacts: 'tidb/tests/integrationtest2/logs/*.log', allowEmptyArchive: true)
                }
            }
        }
    }
}
