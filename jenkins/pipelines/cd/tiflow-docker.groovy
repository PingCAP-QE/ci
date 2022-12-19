def podYaml = '''
apiVersion: v1
kind: Pod
metadata:
  name: dinp
  namespace: jenkins-cd
spec:
  containers:
  - name: dockerd
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
  - name: docker
    image: docker
    command: ["sleep", "infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
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
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow.git']]]
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
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh """
                                    sed '/RUN make engine/ i  ENV GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn,direct' -i deployments/engine/docker/Dockerfile
                                    cat deployments/engine/docker/Dockerfile
                                """
                                sh "docker build --platform linux/amd64  -f deployments/engine/docker/Dockerfile -t hub.pingcap.net/pingcap/tiflow:${ImageTag}-amd64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow:${ImageTag}-amd64"
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
                        stage("checkout") {
                            steps{
                                checkout scm: [$class: 'GitSCM',
                                               branches: [[name: GitHash]],
                                               extensions: [[$class: 'LocalBranch']],
                                               userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tiflow.git']]]
                            }
                        }
                        stage('build docker') {
                            steps {
                                sh """
                                    sed '/RUN make engine/ i  ENV GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn,direct' -i deployments/engine/docker/Dockerfile
                                    cat deployments/engine/docker/Dockerfile
                                """
                                sh "docker build --platform linux/arm64  -f deployments/engine/docker/Dockerfile -t hub.pingcap.net/pingcap/tiflow:${ImageTag}-arm64 ."
                                sh "docker push hub.pingcap.net/pingcap/tiflow:${ImageTag}-arm64"
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
                sh "docker manifest create hub.pingcap.net/pingcap/tiflow:${ImageTag} --amend hub.pingcap.net/pingcap/tiflow:${ImageTag}-amd64 hub.pingcap.net/pingcap/tiflow:${ImageTag}-arm64"
                sh "docker manifest push --purge hub.pingcap.net/pingcap/tiflow:${ImageTag}"
            }
        }

        stage("sync images"){
            parallel{
                stage("sync git hash to gcr"){
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow:${ImageTag}"),
                        ])
                    }
                }
                stage("sync branch name or git tag as image tag to gcr"){
                    when { not { equals expected: ImageTag, actual: params.Revision } }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow:${params.Revision}"),
                        ])
                    }
                }
                stage("sync latest tag to gcr"){
                    when { equals expected: 'master', actual: params.Revision }
                    steps{
                        build(job: "jenkins-image-syncer", parameters: [
                                string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/pingcap/tiflow:${ImageTag}"),
                                string(name: 'TARGET_IMAGE', value: "gcr.io/pingcap-public/tidbcloud/tiflow:latest"),
                        ])
                    }
                }
            }
        }
    }
}
