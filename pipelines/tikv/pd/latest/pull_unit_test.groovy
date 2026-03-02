// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-pd"
final GIT_CREDENTIALS_ID = 'github-sre-bot-ssh'
final GIT_FULL_REPO_NAME = 'tikv/pd'
final POD_TEMPLATE_FILE = 'pipelines/tikv/pd/latest/pod-pull_unit_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
            defaultContainer 'golang'
        }
    }
    options {
        timeout(time: 15, unit: 'MINUTES')
        // parallelsAlwaysFailFast()
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

        stage('Tests') {
            matrix {
                axes {
                    axis {
                        name 'JOB_INDEX'
                        values "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"
                    }
                }
                agent{
                    kubernetes {
                        namespace K8S_NAMESPACE
                        yamlFile POD_TEMPLATE_FILE
                        defaultContainer 'golang'
                        retries 5
                    }
                }
                stages {
                    stage("Test") {
                        steps {
                            dir("pd") {
                                cache(path: "./", includes: '**/*', key: prow.getCacheKey('git', REFS)) {
                                    sh label: "Test Index ${JOB_INDEX}", script: """
                                        make ci-test-job JOB_INDEX=${JOB_INDEX}
                                        mv covprofile covprofile_${JOB_INDEX}
                                        if [ -f junitfile ]; then
                                            cat junitfile
                                        fi
                                        ls -alh covprofile_*
                                    """
                                }
                            }
                        }
                        // post {
                        //     success {
                        //         script {
                        //             dir("pd") {
                        //                 sh label: 'Upload coverage', script: """
                        //                     skip_upload_coverage=true
                        //                     echo "skip_upload_coverage: $skip_upload_coverage"
                        //                 """
                        //             }
                        //         }
                        //     }
                        // }
                    }
                }
            }
        }

    }
}
