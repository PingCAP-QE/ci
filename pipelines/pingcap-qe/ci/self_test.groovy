@Library('tipipeline') _

pipeline {
    agent {
        kubernetes { 
            label ""
        }
    }
    stages{
        stage('run example') {
            steps{
                echo(message: env.BUILD_NUMBER)
                script {
                    log.info("info from share library's globa var function")
                    hello
                }
                sh '[ $(($(date +%s) % 2)) -eq 0 ]' // random failed with 50% percent.
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