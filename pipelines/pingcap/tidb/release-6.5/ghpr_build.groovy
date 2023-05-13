// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.5/pod-ghpr_build.yaml'
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
        timeout(time: 30, unit: 'MINUTES')
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
                    ls -l /dev/null
                    echo "debug command: kubectl -n ${K8S_NAMESPACE} exec -ti ${NODE_NAME} bash"
                """
                container(name: 'net-tool') {
                    sh 'dig github.com'
                }
            }
        }
        stage('Checkout') {
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            parallel {   
                stage('tidb') {
                    steps {
                        dir('tidb') {
                            cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                                retry(2) {
                                    script {
                                        prow.checkoutRefs(REFS)
                                    }
                                }
                            }
                            sh label: "check git workspace clean", script: """
                                git status
                                git clean -d -f -f
                                if [[ -z \$(git status -s) ]]
                                then
                                    echo "tree is clean"
                                else
                                    echo "tree is dirty, please contact ee team to clean up"
                                    exit
                                fi
                            """
                        }
                    }
                }
                stage("enterprise-plugin") {
                    steps {
                        dir("enterprise-plugin") {
                            cache(path: "./", filter: '**/*', key: "git/pingcap/enterprise-plugin/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/enterprise-plugin/rev-']) {
                                retry(2) {
                                    script {
                                        component.checkout('git@github.com:pingcap/enterprise-plugin.git', 'plugin', REFS.base_ref, REFS.pulls[0].title, GIT_CREDENTIALS_ID)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        stage("Build tidb-server and plugin"){
            parallel {
                stage("Build tidb-server") {
                    stages {
                        stage("Build"){
                            steps {
                                dir("tidb") {                                  
                                    sh "make bazel_build"
                                }
                            }
                            post {       
                                // TODO: statics and report logic should not put in pipelines.
                                // Instead should only send a cloud event to a external service.
                                always {
                                    dir("tidb") {
                                        archiveArtifacts(
                                            artifacts: 'importer.log,tidb-server-check.log',
                                            allowEmptyArchive: true,
                                        )
                                    }            
                                }
                            }
                        }
                        stage("Upload") {
                            options {
                                timeout(time: 5, unit: 'MINUTES')
                            }
                            steps {
                                dir("tidb") {
                                    sh label: "create tidb-server tarball", script: """
                                        rm -rf .git
                                        tar czvf tidb-server.tar.gz ./*
                                        echo "pr/${REFS.pulls[0].sha}" > sha1
                                        echo "done" > done
                                        """
                                    sh label: 'upload to tidb dir', script: """
                                        filepath="builds/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].sha}/centos7/tidb-server.tar.gz"
                                        donepath="builds/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].sha}/centos7/done"
                                        refspath="refs/${GIT_FULL_REPO_NAME}/pr/${REFS.pulls[0].number}/sha1"
                                        curl -F \${filepath}=@tidb-server.tar.gz \${FILE_SERVER_URL}/upload
                                        curl -F \${donepath}=@done \${FILE_SERVER_URL}/upload
                                        curl -F \${refspath}=@sha1 \${FILE_SERVER_URL}/upload
                                        """
                                    sh label: 'upload to tidb-checker dir', script: """
                                        filepath="builds/pingcap/tidb-check/pr/${REFS.pulls[0].sha}/centos7/tidb-server.tar.gz"
                                        donepath="builds/pingcap/tidb-check/pr/${REFS.pulls[0].sha}/centos7/done"
                                        curl -F \${filepath}=@tidb-server.tar.gz \${FILE_SERVER_URL}/upload
                                        curl -F \${donepath}=@done \${FILE_SERVER_URL}/upload                                    
                                        """
                                }
                            }
                        }
                    }
                }
                stage("Build plugins") {
                    steps {
                        timeout(time: 15, unit: 'MINUTES') {
                            sh label: 'build pluginpkg tool', script: 'cd tidb/cmd/pluginpkg && go build'
                        }
                        dir('enterprise-plugin/whitelist') {
                            sh label: 'build plugin whitelist', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                        dir('enterprise-plugin/audit') {
                            sh label: 'build plugin: audit', script: '''
                                GO111MODULE=on go mod tidy
                                ../../tidb/cmd/pluginpkg/pluginpkg -pkg-dir . -out-dir .
                                '''
                        }
                    }
                }
            }
        }
    }
    post {
        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {
                sh "bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json || true"
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
