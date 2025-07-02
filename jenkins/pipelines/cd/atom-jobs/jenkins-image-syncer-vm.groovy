pipeline {
    parameters {
        string(name: 'SOURCE_IMAGE', description: 'source image name, habor')
        string(name: 'TARGET_IMAGE', description: 'target image name, gcr or dockerhub')
    }
    agent {label 'jenkins-image-syncer'}

    stages {
        stage('Login to DockerHub and Harbor') {
            steps {
                script {
                    withCredentials([file(credentialsId: 'gcr-registry-key', variable: 'GCR_KEY')]) {
                        retry(3) {
                            sh """
                            cp \$GCR_KEY keyfile.json
                            cat keyfile.json | docker login -u _json_key --password-stdin https://gcr.io
                            """
                        }
                    }

                    withCredentials([usernamePassword(credentialsId: 'hub.docker.com-pingcap', usernameVariable: 'username', passwordVariable: 'password')]) {
                        sh """
                        echo "${password}" | docker login --username pingcap --password-stdin
                        """
                    }

                    withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                        sh """
                        docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
                        """
                    }
            }
        }
        }

        stage('Prepare regctl Image') {
            steps {
                sh """
                wget -q http://fileserver.pingcap.net/download/github-actions/regctl-linux-amd64
                chmod +x regctl-linux-amd64
                """
            }
        }
        stage('Sync Image') {
            steps {
                retry(3) {
                sh """
                ./regctl-linux-amd64 image copy ${SOURCE_IMAGE} ${TARGET_IMAGE} -v info
                """
                }
            }
        }
    }
}
