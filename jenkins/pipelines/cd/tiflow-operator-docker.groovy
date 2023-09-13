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
    triggers {
        cron('@daily')
    }
    parameters {
        string(name: 'Revision', defaultValue: 'master', description: 'branch or commit hash')
    }
    options {
        timeout(time: 40, unit: 'MINUTES')
    }
    stages {
        stage ("get commit hash") {
            agent {
                kubernetes {
                    yaml podYaml
                    nodeSelector "kubernetes.io/arch=amd64"
                    defaultContainer 'docker'
                }
            }
            steps{
                script {
                    def scmVars = checkout([$class: 'GitSCM',
                                branches: [[name: params.Revision]],
                                extensions: [[$class: 'LocalBranch']],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow-operator.git']]]
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
                            nodeSelector "kubernetes.io/arch=amd64"
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
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow-operator.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "docker build --platform linux/amd64 -t hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-amd64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-amd64"
                            }
                        }
                    }
                }
                stage("arm64"){
                    agent {
                        kubernetes {
                            yaml podYaml
                            cloud "kubernetes"
                            nodeSelector "kubernetes.io/arch=arm64"
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
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow-operator.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh "docker build --platform linux/arm64 -t hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-arm64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-arm64"
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
                    nodeSelector "kubernetes.io/arch=amd64"
                    defaultContainer 'docker'
                }
            }
            steps{
                sh 'printenv HUB_PSW | docker login hub.pingcap.net -u ${HUB_USR} --password-stdin'
                sh "docker manifest create hub.pingcap.net/pingcap/tiflow-operator:${ImageTag} --amend hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-amd64 hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}-arm64"
                sh "docker manifest push --purge hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}"
            }
        }

        stage("sync images"){
            parallel{
                stage("sync git hash tag to gcr"){
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow-operator:${ImageTag}"),
                        ])
                    }
                }
                stage("sync branch name or git tag as image tag to gcr"){
                    when { not { equals expected: ImageTag, actual: params.Revision } }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow-operator:${params.Revision}"),
                        ])
                    }
                }
                stage("sync latest tag to gcr"){
                    when { equals expected: 'master', actual: params.Revision }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow-operator:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow-operator:latest"),
                        ])
                    }
                }
            }
        }
    }
}
