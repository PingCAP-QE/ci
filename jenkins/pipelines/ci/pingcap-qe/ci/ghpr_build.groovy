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
                sh '[ $((${env.BUILD_NUMBER} % 2)) -eq 0 ]' // random failed with 50% percent.
            }
        }
    }
}