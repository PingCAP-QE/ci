def validateScript = """
curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh
export PATH=\$PATH:\$HOME/.tiup/bin
tiup mirror reset
tiup install tikv:${params.VERSION} tidb:${params.VERSION} pd:${params.VERSION} tiflash:${params.VERSION} cdc:${params.VERSION}
"""

final tiupPodPath = "jenkins/pipelines/cd/tiup/tiup-pod.yaml"

pipeline {
    parameters {
        string defaultValue: 'nightly', description: '', name: 'VERSION', trim: true
    }
    agent none
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage("MultiPlatform") {
            parallel {
                stage('linux/amd64') {
                    agent {
                        kubernetes {
                            yamlFile tiupPodPath
                            defaultContainer 'tiup'
                            nodeSelector "kubernetes.io/arch=amd64"
                        }
                    }
                    steps {
                        sh validateScript
                    }
                }
                stage('linux/arm64') {
                    agent {
                        kubernetes {
                            yaml tiupPodPath
                            defaultContainer 'tiup'
                            nodeSelector "kubernetes.io/arch=arm64"
                        }
                    }
                    steps {
                        sh validateScript
                    }
                }
                stage('darwin/arm64') {
                    agent {
                        node {
                            label 'darwin && arm64'
                        }
                    }
                    steps {
                        sh validateScript
                    }
                }
                stage('darwin/amd64') {
                    agent {
                        node {
                            label 'darwin && amd64'
                        }
                    }
                    steps {
                        sh validateScript
                    }
                }
            }
        }
    }
}
