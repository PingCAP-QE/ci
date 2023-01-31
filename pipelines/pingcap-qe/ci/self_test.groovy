@Library('tipipeline') _

final POD_TEMPLATE_FILE = 'pipelines/pingcap-qe/ci/pod-self_test.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

// xxx

pipeline {
    agent {
        kubernetes {
            yamlFile POD_TEMPLATE_FILE
            defaultContainer('main')
        }
    }
    stages{
        stage('Validate pipelines') {
            steps{
                sh '''#!/bin/bash +x
                
                # Linting via HTTP POST using curl
                # curl (REST API)
                # Assuming "anonymous read access" has been enabled on your Jenkins instance.
                # JENKINS_URL=[root URL of Jenkins controller]
                # JENKINS_CRUMB is needed if your Jenkins controller has CRSF protection enabled as it should
                
                JENKINS_CRUMB=`curl -fsS "$JENKINS_URL/crumbIssuer/api/json" | jq .crumb`
                for f in `find pipelines -name "*.groovy"`; do
                    echo -ne "validating $f:\\t"
                    curl -fsS -X POST -H $JENKINS_CRUMB -F "jenkinsfile=<${f}" $JENKINS_URL/pipeline-model-converter/validate
                done
                '''
            }
        }
    }
    post{
        success {
            echo "passed ........"
        }
        failure {
            echo "failed~~~~~"
        }        
    }
}
