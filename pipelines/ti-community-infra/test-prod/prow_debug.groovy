// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches
@Library('tipipeline') _
final POD_TEMPLATE_FILE = 'pipelines/ti-community-infra/test-prod/pod-prow_debug.yaml'

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options { skipDefaultCheckout() }
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: '''
                    printenv
                    echo "-------------------------"
                    ls -la
                '''
            }
        }
        stage('Checkout') {
            when { expression { return params.JOB_SPEC } }
            steps {
                dir('test') {
                    script {
                        prow.checkoutRefs(readJSON(text: params.JOB_SPEC).refs)
                    }
                    sh "pwd && ls -l"
                }
                sh "pwd && ls -l"
            }
        }
    }
}
