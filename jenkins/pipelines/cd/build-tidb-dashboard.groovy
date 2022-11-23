package cd

final dockerfile = '''
FROM hub.pingcap.net/bases/pingcap_base:v1 as builder
ARG TARGETARCH

RUN sed -e 's|^mirrorlist=|#mirrorlist=|g' \\
        -e 's|^#baseurl=http://dl.rockylinux.org/$contentdir|baseurl=https://mirrors.sjtug.sjtu.edu.cn/rocky|g' \\
        -i.bak \\
        /etc/yum.repos.d/rocky-extras.repo \\
        /etc/yum.repos.d/rocky.repo \\
    && dnf makecache
RUN dnf install -y\\
    make \\
    git \\
    bash \\
    curl \\
    findutils \\
    gcc \\
    glibc-devel \\
    nodejs \\
    npm \\
    java-11-openjdk-devel &&  \\
    dnf clean all
RUN curl -o /tmp/go.tar.gz https://dl.google.com/go/go1.18.8.linux-${TARGETARCH}.tar.gz &&\\
     tar -xzf /tmp/go.tar.gz -C /usr/local && ln -s /usr/local/go/bin/go /usr/local/bin/go &&\\
     rm /tmp/go.tar.gz
RUN npm install -g pnpm

RUN mkdir -p /root/tidb-dashboard/ui
WORKDIR /root/tidb-dashboard/ui
COPY ui/pnpm-lock.yaml .
RUN pnpm fetch
WORKDIR /root/tidb-dashboard
COPY go.mod go.sum ./
RUN go mod download
COPY scripts/ scripts/
RUN scripts/install_go_tools.sh

COPY . .
RUN make package PNPM_INSTALL_TAGS=--offline

FROM hub.pingcap.net/bases/pingcap_base:v1.0.0

COPY --from=builder /root/tidb-dashboard/bin/tidb-dashboard /tidb-dashboard

EXPOSE 12333

ENTRYPOINT ["/tidb-dashboard"]

'''


final podYaml='''
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: uploader
      image: hub.pingcap.net/jenkins/uploader
      args: ["sleep", "infinity"]
    - name: builder
      image: hub.pingcap.net/jenkins/docker-builder
      args: ["sleep", "infinity"]
      env:
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

final dockerSyncYaml = '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: regctl
    image: hub.pingcap.net/jenkins/regctl
    args: ["sleep", "infinity"]
  tolerations:
  - effect: NoSchedule
    key: tidb-operator
    operator: Exists
'''

pipeline {
    agent none
    parameters {
        string(name: 'GitRef', defaultValue: 'master', description: 'branch or commit hash')
        string(name: 'ReleaseTag', defaultValue: 'test', description: 'empty means the same with GitRef')
    }
    stages {
        stage("build multi-arch"){
            parallel{
                stage("linux/amd64"){
                    environment { HUB = credentials('harbor-pingcap') }
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'builder'
                            cloud "kubernetes-ng"
                            namespace "jenkins-tidb-operator"
                        }
                    }
                    steps {
                        checkout changelog: false, poll: false, scm: [
                                $class           : 'GitSCM',
                                branches         : [[name: "${params.GitRef}"]],
                                userRemoteConfigs: [[
                                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                            url    : 'https://github.com/pingcap/tidb-dashboard.git',
                                                    ]]
                        ]
                        sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                        sh 'docker buildx create --name mybuilder --use || true'
                        writeFile file:'build-tidb-dashboard.Dockerfile',text:dockerfile
                        sh 'cat build-tidb-dashboard.Dockerfile'
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-amd64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-amd64 --push --platform=linux/amd64 --build-arg BUILDKIT_INLINE_CACHE=1 --progress=plain"
                        sh """
                    docker run --platform=linux/amd64 --name=amd64 --entrypoint=/bin/cat  hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-amd64
                    mkdir -p bin/linux-amd64/
                    docker cp amd64:/tidb-dashboard bin/linux-amd64/tidb-dashboard
                    docker stop amd64
                    docker container prune -f
                """
                        container("uploader") {
                            sh """tar -cvzf tidb-dashboard-linux-amd64.tar.gz -C bin/linux-amd64 tidb-dashboard"""
                            sh "curl -F build/tidb-dashboard/${params.ReleaseTag}/linux-amd64.tar.gz=@tidb-dashboard-linux-amd64.tar.gz http://fileserver.pingcap.net/upload"
                        }
                    }
                }
                stage("linux/arm64"){
                    environment { HUB = credentials('harbor-pingcap') }
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'builder'
                            cloud "kubernetes-arm64"
                            namespace "jenkins-cd"
                        }
                    }
                    steps {
                        checkout changelog: false, poll: false, scm: [
                                $class           : 'GitSCM',
                                branches         : [[name: "${params.GitRef}"]],
                                userRemoteConfigs: [[
                                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                            url    : 'https://github.com/pingcap/tidb-dashboard.git',
                                                    ]]
                        ]
                        sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                        sh 'docker buildx create --name mybuilder --use || true'
                        writeFile file:'build-tidb-dashboard.Dockerfile',text:dockerfile
                        sh 'cat build-tidb-dashboard.Dockerfile'
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-arm64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-arm64 --push --platform=linux/arm64 --build-arg BUILDKIT_INLINE_CACHE=1 --progress=plain"
                        sh """
                    docker run --platform=linux/arm64 --name=arm64 --entrypoint=/bin/cat  hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-arm64
                    mkdir -p bin/linux-arm64/
                    docker cp arm64:/tidb-dashboard bin/linux-arm64/tidb-dashboard
                    docker stop arm64
                    docker container prune -f
                """
                        container("uploader") {
                            sh """tar -cvzf tidb-dashboard-linux-arm64.tar.gz -C bin/linux-arm64 tidb-dashboard"""
                            sh "curl -F build/tidb-dashboard/${params.ReleaseTag}/linux-arm64.tar.gz=@tidb-dashboard-linux-arm64.tar.gz http://fileserver.pingcap.net/upload"
                        }
                    }
                }
            }
        }
        stage("docker manifest"){
            environment { HUB = credentials('harbor-pingcap') }
            agent {
                kubernetes {
                    yaml podYaml
                    defaultContainer 'builder'
                    cloud "kubernetes-ng"
                    namespace "jenkins-tidb-operator"
                }
            }
            steps {
                sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                sh """docker manifest create hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag} -a hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-amd64 hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}-arm64
                      docker manifest push hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}
                """
            }
        }
        stage("sync to qa"){
            agent {
                kubernetes {
                    yaml dockerSyncYaml
                    defaultContainer 'regctl'
                    cloud "kubernetes-ng"
                    namespace "jenkins-tidb-operator"
                }
            }
            environment { HUB = credentials('harbor-pingcap') }
            steps {
                sh 'set +x; regctl registry login hub.pingcap.net -u $HUB_USR -p $(printenv HUB_PSW)'
                sh "regctl image copy hub.pingcap.net/rc/tidb-dashboard:${ReleaseTag}  hub.pingcap.net/qa/tidb-dashboard:${ReleaseTag}"
            }
        }
    }
}
