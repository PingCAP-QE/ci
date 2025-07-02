
podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: check
    image: hub.pingcap.net/jenkins/release-check-version:v20240326
    imagePullPolicy: Always
    args: ["sleep", "infinity"]
"""

pipeline {
    parameters {
        string(
            defaultValue: "v8.0.0",
            description: 'The release version to check',
            name: 'VERSION',
            trim: true
        )
        string(
            defaultValue: 'https://raw.githubusercontent.com/purelind/test-ci/main/components-v8.0.0.json' ,
            name: 'COMPONENT_JSON_URL',
            description: 'The URL of the component json file',
            trim: true
        )
    }
    agent none
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage("Check URL") {
            agent {
                kubernetes {
                    yaml podYaml
                    defaultContainer 'check'
                    nodeSelector "kubernetes.io/arch=amd64"
                }
            }
            steps {
                script {
                    if (params.COMPONENT_JSON_URL == "") {
                        error("The COMPONENT_JSON_URL is empty")
                    }
                    dir("release-check-offline") {
                        script {
                            sh """

                            git clone --branch main --depth 1 https://github.com/PingCAP-QE/ci.git .
                            cd scripts/ops/release-check-version
                            python3 check_offline_package.py quick ${VERSION} community amd64 --components_url="${params.COMPONENT_JSON_URL}"
                            """
                        }
                    }
                }
            }
        }
        stage("MultiPlatform check") {
            parallel {
                stage('linux/amd64 community') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=amd64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                tiup --version
                                python3 --version

                                git clone --branch main --depth 1 https://github.com/PingCAP-QE/ci.git .
                                cd scripts/ops/release-check-version
                                python3 check_offline_package.py details ${params.VERSION} community amd64 --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }
                stage('linux/arm64 community') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=arm64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                tiup --version
                                python3 --version

                                git clone --branch main --depth 1 https://github.com/PingCAP-QE/ci.git .
                                cd scripts/ops/release-check-version
                                python3 check_offline_package.py details ${params.VERSION} community arm64 --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }
                stage('linux/amd64 enterprise') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=amd64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                tiup --version
                                python3 --version

                                git clone --branch main --depth 1 https://github.com/PingCAP-QE/ci.git .
                                cd scripts/ops/release-check-version
                                python3 check_offline_package.py details ${params.VERSION} enterprise amd64 --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }
                stage('linux/arm64 enterprise') {
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'check'
                            nodeSelector "kubernetes.io/arch=arm64"
                        }
                    }
                    steps {
                        dir("release-check-version") {
                            script {
                                sh """
                                tiup --version
                                python3 --version

                                git clone --branch main --depth 1 https://github.com/PingCAP-QE/ci.git .
                                cd scripts/ops/release-check-version
                                python3 check_offline_package.py details ${params.VERSION} enterprise arm64 --components_url="${params.COMPONENT_JSON_URL}"
                                """
                            }
                        }
                    }
                }
            }
        }
    }
}
