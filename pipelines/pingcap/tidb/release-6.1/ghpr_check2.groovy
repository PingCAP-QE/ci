// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for release-6.2.x branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/release-6.1/pod-ghpr_check2.yaml'

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        parallelsAlwaysFailFast()
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
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
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${ghprbActualCommit}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            checkout(
                                changelog: false,
                                poll: false,
                                scm: [
                                    $class: 'GitSCM', branches: [[name: ghprbActualCommit]],
                                    doGenerateSubmoduleConfigurations: false,
                                    extensions: [
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                        [$class: 'CloneOption', timeout: 5],
                                    ],
                                    submoduleCfg: [],
                                    userRemoteConfigs: [[
                                        refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                        url: "https://github.com/${GIT_FULL_REPO_NAME}.git"
                                    ]],
                                ]
                            )
                        }
                    }
                }
            }
        }
        stage("Prepare") {
            steps {
                dir('tidb') {
                    cache(path: "./bin", filter: '**/*', key: "binary/pingcap/tidb/tidb-server/rev-${ghprbActualCommit}") {
                        sh label: 'tidb-server', script: 'ls bin/tidb-server || go build -o bin/tidb-server github.com/pingcap/tidb/tidb-server'
                    }
                    script {
                         component.fetchAndExtractArtifact(FILE_SERVER_URL, 'tikv', ghprbTargetBranch, ghprbCommentBody, 'centos7/tikv-server.tar.gz', 'bin')
                         component.fetchAndExtractArtifact(FILE_SERVER_URL, 'pd', ghprbTargetBranch, ghprbCommentBody, 'centos7/pd-server.tar.gz', 'bin')
                    }
                    // cache it for other pods
                    cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                        sh '''
                            mv bin/tidb-server bin/explain_test_tidb-server
                            touch rev-${ghprbActualCommit}
                        '''
                    }
                }
            }
        }
        stage('Checks') {
            matrix {
                axes {
                    axis {
                        name 'SCRIPT_AND_ARGS'
                        values(
                            'explaintest.sh y', 
                            'explaintest.sh n', 
                        )
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        defaultContainer 'golang'
                        yamlFile POD_TEMPLATE_FILE
                    }
                }
                stages {                    
                    stage('Test')  {
                        options { timeout(time: 30, unit: 'MINUTES') }
                        steps {
                            dir('tidb') {
                                cache(path: "./", filter: '**/*', key: "ws/${BUILD_TAG}") {
                                    sh 'ls -l rev-${ghprbActualCommit}' // will fail when not found in cache or no cached.
                                }

                                sh 'chmod +x ../scripts/pingcap/tidb/*.sh'
                                sh "${WORKSPACE}/scripts/pingcap/tidb/${SCRIPT_AND_ARGS}"
                            }
                        }
                        post {
                            failure {
                                dir("checks-collation-enabled") {
                                    archiveArtifacts(artifacts: 'pd*.log, tikv*.log, explain-test.out', allowEmptyArchive: true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            // Upload check flag to fileserver
            sh "echo done > done && curl -F ci_check/${JOB_NAME}/${ghprbActualCommit}=@done ${FILE_SERVER_URL}/upload"
        }

        // TODO(wuhuizuo): put into container lifecyle preStop hook.
        always {
            container('report') {                
                sh "bash scripts/plugins/report_job_result.sh ${currentBuild.result} result.json || true"
            }
            archiveArtifacts(artifacts: 'result.json', fingerprint: true, allowEmptyArchive: true)
        }
    }
}
