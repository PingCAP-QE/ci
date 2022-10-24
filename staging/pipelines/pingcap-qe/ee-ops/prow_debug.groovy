// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches

final POD_TEMPLATE_FILE = 'staging/pipelines/pingcap-qe/ee-ops/pod-prow_debug.yaml'

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
        }
    }
    
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: '''
                    printenv                   
                    echo "-------------------------"
                    echo "debug, you can rm the file debug.txt to continue"
                    touch debug.txt
                    while [ -f debug.txt ]; do sleep 1; done
                    echo "continue"
                    echo "-------------------------"
                    ls -la
                '''
            }
        }
    }
}