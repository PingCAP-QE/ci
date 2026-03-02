// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/pd'
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/release-6.5-fips/pod-ghpr_build.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    environment {
        OCI_ARTIFACT_HOST = 'hub-zot.pingcap.net/mirrors/hub'
        ENABLE_FIPS = 1
    }
    options {
        timeout(time: 15, unit: 'MINUTES')
        parallelsAlwaysFailFast()
        skipDefaultCheckout()
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
                    script {
                        prow.setPRDescription(REFS)
                    }
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("pd") {
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
        stage('Build') {
            steps {
                dir('pd') {
                    sh '''
                        WITH_RACE=1 make && mv bin/pd-server bin/pd-server-race
                        make
                    '''
                }
            }
        }
        stage("Upload") {
            environment {
                HUB = credentials('harbor-pingcap')
            }
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                dir('pd') {
                    container('utils') {
                        sh label: 'upload pd-server to OCI', script: """
                            cp bin/pd-server ./pd-server
                            tarball="pd-v0.0.0-pr-${REFS.pulls[0].number}.tar.gz"
                            tar czvf "\${tarball}" pd-server
                            oci_tag="pr-${REFS.pulls[0].number}_linux_amd64"
                            oci_url="${OCI_ARTIFACT_HOST}/tikv/pd/package:\${oci_tag}"
                            printenv HUB_PSW | oras login ${OCI_ARTIFACT_HOST} -u \${HUB_USR} --password-stdin
                            oras push --artifact-type application/gzip "\${oci_url}" "\${tarball}"
                            echo "✅ Uploaded pd-server to OCI: \${oci_url}"
                            """
                    }
                }
            }
        }
    }
}
