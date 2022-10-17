def podYaml = '''
apiVersion: v1
kind: Pod
metadata:
  name: dinp
  namespace: jenkins-cd
spec:
  containers:
  - image: docker
    name: docker
    command: ["sleep", "infinity"]
    env:
    - name: DOCKER_TLS_VERIFY
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
  - name: dind
    image: docker:dind
    args: ["--registry-mirror=https://registry-mirror.pingcap.net"]
    env:
    - name: REGISTRY
      value: hub.pingcap.net
    - name: DOCKER_TLS_CERTDIR
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    securityContext:
      privileged: true
    tty: true
    readinessProbe:
      exec:
        command: ["docker", "info"]
      initialDelaySeconds: 10
      failureThreshold: 6
  - name: jnlp
    image: jenkins/inbound-agent:4.10-3
'''

def BasicTag = params.REVISION

pipeline {
    agent none
    triggers {
        cron('@daily')
    }
    parameters {
        string(name: 'REVISION', defaultValue: 'master', description: 'branch or tag or hash')
    }
    stages {
        stage ("parallel build docker by arch") {
            parallel{
                stage("amd64"){
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'docker'
                        }
                    }
                    stages{
                        stage('configure docker'){
                            environment {HUB = credentials('harbor-pingcap')}
                            steps{
                                sh 'printenv HUB_PSW | docker login hub.pingcap.net -u ${HUB_USR} --password-stdin'
                            }
                        }
                        stage("checkout"){
                            steps{
                                checkout scm: [$class: 'GitSCM',
                                               branches: [[name: params.REVISION]],
                                               extensions: [[$class: 'LocalBranch']],
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "docker build --platform linux/amd64  -f deployments/engine/docker/Dockerfile -t hub.pingcap.net/pingcap/tiflow:${BasicTag}-amd64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow:${BasicTag}-amd64"
                            }
                        }
                    }
                }
                stage("arm64"){
                    agent {
                        kubernetes {
                            yaml podYaml
                            cloud "kubernetes-arm64"
                            defaultContainer 'docker'
                        }
                    }
                    stages{
                        stage('configure docker'){
                            environment {HUB = credentials('harbor-pingcap')}
                            steps{
                                sh 'printenv HUB_PSW | docker login hub.pingcap.net -u ${HUB_USR} --password-stdin'
                            }
                        }
                        stage("checkout"){
                            steps{
                                checkout scm: [$class: 'GitSCM',
                                               branches: [[name: params.REVISION]],
                                               extensions: [[$class: 'LocalBranch']],
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "docker build --platform linux/arm64  -f deployments/engine/docker/Dockerfile -t hub.pingcap.net/pingcap/tiflow:${BasicTag}-arm64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow:${BasicTag}-arm64"
                            }
                        }
                    }
                }

            }
        }
        stage("build multi-arch docker image"){
            environment {HUB = credentials('harbor-pingcap')}
            agent {
                kubernetes {
                    yaml podYaml
                    defaultContainer 'docker'
                }
            }
            steps{
                sh 'printenv HUB_PSW | docker login hub.pingcap.net -u ${HUB_USR} --password-stdin'
                sh "docker manifest create hub.pingcap.net/pingcap/tiflow:${BasicTag} --amend hub.pingcap.net/pingcap/tiflow:${BasicTag}-amd64 hub.pingcap.net/pingcap/tiflow:${BasicTag}-arm64"
                sh "docker manifest push --purge hub.pingcap.net/pingcap/tiflow:${BasicTag}"
            }
        }

        stage("sync images"){
            parallel{
                stage("sync to gcr"){
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${BasicTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow:${BasicTag}"),
                        ])
                    }
                }
                stage("sync latest to hub"){
                    when { equals expected: 'master', actual: params.REVISION }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${BasicTag}"),
                                string(name: 'TARGET_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:latest"),
                        ])
                    }
                }
                stage("sync latest to gcr"){
                    when { equals expected: 'master', actual: params.REVISION }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${BasicTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow:latest"),
                        ])
                    }
                }
            }
        }
    }
}
