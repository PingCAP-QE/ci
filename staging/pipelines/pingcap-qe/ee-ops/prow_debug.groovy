// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest release branches

// TODO(wuhuizuo): tidb-test should delivered by docker image.
pipeline {
    agent {
        kubernetes {
        }
    }
    
    stages {
        stage('Debug info') {
            steps {
                sh label: 'Debug info', script: """
                    printenv                   
                    echo "-------------------------"
                    pwd
                    echo "-------------------------"
                    ls -la
                """               
            }
        }
    }
}