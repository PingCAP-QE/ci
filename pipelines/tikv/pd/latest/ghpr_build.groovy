// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/pd'
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/latest/pod-ghpr_build.yaml'
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
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
                }
            }
        }
        stage('Checkout') {
            options { timeout(time: 5, unit: 'MINUTES') }
            steps {
                dir("pd") {
                    cache(path: "./", filter: '**/*', key: "git/tikv/pd/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/tikv/pd/rev-']) {
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
            options {
                timeout(time: 5, unit: 'MINUTES')
            }
            steps {
                dir('pd') {
                    sh label: "create pd-server tarball", script: """
                        rm -rf .git
                        tar czvf pd-server.tar.gz ./*
                        echo "pr/${REFS.pulls[0].sha}" > sha1
                        """
                    // FIXME(wuhuizuo): filepath is wrong, should renew to tikv/pd
                    sh label: 'upload to pd dir', script: """
                        filepath="builds/pingcap/pd/pr/${REFS.pulls[0].sha}/centos7/pd-server.tar.gz"
                        refspath="refs/pingcap/pd/pr/${REFS.pulls[0].number}/sha1"
                        curl -F \${filepath}=@pd-server.tar.gz \${FILE_SERVER_URL}/upload
                        curl -F \${refspath}=@sha1 \${FILE_SERVER_URL}/upload
                        """
                }
            }
        }
    }
}
