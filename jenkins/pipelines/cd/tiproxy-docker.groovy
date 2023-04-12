package cd

def podYaml = '''
apiVersion: v1
kind: Pod
metadata:
  name: dinp
  namespace: jenkins-cd
spec:
  containers:
  - name: docker
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
'''

def GitHash = ''
def ImageTag = ''
pipeline {
    agent none
    parameters {
        string(name: 'Revision', defaultValue: 'main', description: 'branch or commit hash')
    }
    stages {
        stage ("get commit hash") {
            agent {
                kubernetes {
                    yaml podYaml
                    defaultContainer 'docker'
                }
            }
            steps{
                script {
                    def scmVars = checkout([$class: 'GitSCM',
                                branches: [[name: params.Revision]],
                                extensions: [[$class: 'LocalBranch']],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiproxy.git']]]
                    )
                    GitHash = scmVars.GIT_COMMIT
                    ImageTag = GitHash
                    println "git commit hash: ${GitHash}"
                }
            }
        }
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
                        stage("checkout") {
                            steps{
                                checkout scm: [$class: 'GitSCM',
                                               branches: [[name: GitHash]],
                                               extensions: [[$class: 'LocalBranch']],
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiproxy.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "apk update && apk add make"
                                sh "make DOCKER_PREFIX=hub.pingcap.net/pingcap/ IMAGE_TAG=${ImageTag}-amd64 docker"
                                sh "docker push hub.pingcap.net/pingcap/tiproxy:${ImageTag}-amd64"
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
                                               branches: [[name: GitHash]],
                                               extensions: [[$class: 'LocalBranch']],
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiproxy.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "apk update && apk add make"
                                sh "make DOCKER_PREFIX=hub.pingcap.net/pingcap/ IMAGE_TAG=${ImageTag}-arm64 docker"
                                sh "docker push hub.pingcap.net/pingcap/tiproxy:${ImageTag}-arm64"
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
                sh "docker manifest create hub.pingcap.net/pingcap/tiproxy:${ImageTag} --amend hub.pingcap.net/pingcap/tiproxy:${ImageTag}-amd64 hub.pingcap.net/pingcap/tiproxy:${ImageTag}-arm64"
                sh "docker manifest push --purge hub.pingcap.net/pingcap/tiproxy:${ImageTag}"
            }
        }
        stage("sync images"){
            parallel{
                stage("dockerhub: sync git hash"){
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiproxy:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "docker.io/pingcap/tiproxy:${ImageTag}"),
                        ])
                    }
                }
                stage("dockerhub: sync revision"){
                    when { not { equals expected: ImageTag, actual: params.Revision } }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiproxy:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "docker.io/pingcap/tiproxy:${params.Revision}"),
                        ])
                    }
                }
                stage("dockerhub: sync latest tag"){
                    when { equals expected: 'main', actual: params.Revision }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiproxy:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "docker.io/pingcap/tiproxy:latest"),
                        ])
                    }
                }
            }
        }
    }
}
