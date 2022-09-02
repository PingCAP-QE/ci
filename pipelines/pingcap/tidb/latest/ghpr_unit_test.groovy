// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and release-6.2.x branches
final K8S_COULD = "kubernetes-ksyun"
final K8S_NAMESPACE = "jenkins-tidb"

pipeline {
    agent {
        kubernetes {
            cloud K8S_COULD
            namespace K8S_NAMESPACE
            defaultContainer 'golang'
            yamlFile 'pipelines/pingcap/tidb/latest/pod-ghpr_unit_test.yaml'
        }
    }
    environment {
        FILE_SERVER_URL = 'http://fileserver.pingcap.net'
    }
    options {
        timeout(time: 20, unit: 'MINUTES')
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
            environment {
                CACHE_KEEP_COUNT = '10'
            }
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                // restore git repo from cached items.
                container('deno') {
                    sh label: 'restore cache', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                        --op restore \
                        --path tidb \
                        --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                        --key-prefix 'git/pingcap/tidb/rev-'
                    '''
                }

                dir('tidb') {
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
            post{
                success {
                    // cache it if it's new
                    container('deno') {
                        sh label: 'cache it', script: '''deno run --allow-all scripts/plugins/s3-cache.ts \
                            --op backup \
                            --path tidb \
                            --key "git/pingcap/tidb/rev-${ghprbActualCommit}" \
                            --key-prefix 'git/pingcap/tidb/rev-' \
                            --keep-count ${CACHE_KEEP_COUNT}
                        '''
                    }
                }
            }
        }
        stage('Test') {
            steps {
                dir('tidb') {
                    sh './build/jenkins_unit_test.sh' 
                }
            }
            post {
                unsuccessful {
                    dir('tidb') {
                        archiveArtifacts(artifacts: '**/core.*', allowEmptyArchive: true)
                        archiveArtifacts(artifacts: '**/*.test.bin', allowEmptyArchive: true)
                    }
                }
                always {
                    dir('tidb') {
                        // archive test report to Jenkins.
                        junit(testResults: "**/bazel.xml", allowEmptyResults: true)

                        // upload coverage report to file server
                        script {
                            def id = UUID.randomUUID().toString()
                            def filepath = "tipipeline/test/report/${JOB_NAME}/${BUILD_NUMBER}/${id}/report.xml"
                            retry(3) {
                                sh label: "upload coverage report to ${FILE_SERVER_URL}", script: """
                                    curl -F ${filepath}=@test_coverage/bazel.xml ${FILE_SERVER_URL}/upload
                                    echo "coverage download link: ${FILE_SERVER_URL}/download/${filepath}"
                                    """
                            }
                        }                
                    }
                }
            }
        }
    }
}