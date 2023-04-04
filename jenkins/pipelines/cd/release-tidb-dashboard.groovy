package cd

final dockerfile = '''
FROM centos:7 as builder

RUN yum -y update
RUN yum -y groupinstall "Development Tools"

# Install golang.
ENV PATH /usr/local/go/bin:$PATH
RUN export ARCH=$(arch | sed s/aarch64/arm64/ | sed s/x86_64/amd64/) && \\
    export GO_VERSION=1.19.5 && \\
    curl -OL https://golang.org/dl/go$GO_VERSION.linux-$ARCH.tar.gz && \\
    tar -C /usr/local/ -xzf go$GO_VERSION.linux-$ARCH.tar.gz && \\
    rm -f go$GO_VERSION.linux-$ARCH.tar.gz
ENV GOROOT /usr/local/go
ENV GOPATH /go
ENV PATH $GOPATH/bin:$PATH
RUN mkdir -p "$GOPATH/src" "$GOPATH/bin" && chmod -R 777 "$GOPATH"

# Install nodejs.
RUN curl -fsSL https://rpm.nodesource.com/setup_16.x | bash -
RUN yum -y install nodejs
RUN npm install -g pnpm@7.30.5

# Install java.
RUN yum -y install java-11-openjdk

RUN mkdir -p /go/src/github.com/pingcap/tidb-dashboard/ui
WORKDIR /go/src/github.com/pingcap/tidb-dashboard

# Cache go module dependencies.
COPY ./go.mod .
COPY ./go.sum .
RUN go mod download

# Cache go tools.
COPY ./scripts scripts/
RUN scripts/install_go_tools.sh

# Cache npm dependencies.
WORKDIR /go/src/github.com/pingcap/tidb-dashboard/ui
COPY ./ui/pnpm-lock.yaml .
RUN pnpm fetch

# Build.
WORKDIR /go/src/github.com/pingcap/tidb-dashboard
COPY . .
RUN make package PNPM_INSTALL_TAGS=--offline

FROM hub.pingcap.net/bases/pingcap-base:v1

COPY --from=builder /go/src/github.com/pingcap/tidb-dashboard/bin/tidb-dashboard /tidb-dashboard

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
'''

final  tiupYaml='''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: tiup
    image: hub.pingcap.net/jenkins/tiup
    args: ["sleep", "infinity"]
'''

pipeline {
    agent none
    parameters {
        string(name: 'GitRef', defaultValue: 'master', description: 'branch or commit hash')
        string(name: 'ReleaseTag', defaultValue: 'test', description: 'empty means the same with GitRef')
        booleanParam(name: 'PushPublic', defaultValue: false, description: 'whether push to public')
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
                            cloud "kubernetes"
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
                            sh "curl -F builds/tidb-dashboard/${params.ReleaseTag}/linux-amd64.tar.gz=@tidb-dashboard-linux-amd64.tar.gz http://fileserver.pingcap.net/upload"
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
                            sh "curl -F builds/tidb-dashboard/${params.ReleaseTag}/linux-arm64.tar.gz=@tidb-dashboard-linux-arm64.tar.gz http://fileserver.pingcap.net/upload"
                        }
                    }
                }
                stage("darwin/amd64"){
                    agent{
                        label "darwin && amd64"
                    }
                    environment { GOROOT = '/usr/local/go1.18'}
                    steps{
                        checkout changelog: false, poll: false, scm: [
                                $class           : 'GitSCM',
                                branches         : [[name: "${params.GitRef}"]],
                                userRemoteConfigs: [[
                                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                            url    : 'https://github.com/pingcap/tidb-dashboard.git',
                                                    ]]
                        ]
                        sh 'export PATH=$GOROOT/bin:/usr/local/bin:$PATH:/opt/homebrew/bin; make package'
                        sh """
                            tar -cvzf tidb-dashboard-darwin-amd64.tar.gz -C bin/ tidb-dashboard
                            curl -F builds/tidb-dashboard/${params.ReleaseTag}/darwin-amd64.tar.gz=@tidb-dashboard-darwin-amd64.tar.gz http://fileserver.pingcap.net/upload
                           """
                    }
                }
                stage("darwin/arm64"){
                    agent{
                        label "darwin && arm64"
                    }
                    environment { GOROOT = '/usr/local/go1.18'}
                    steps{
                        checkout changelog: false, poll: false, scm: [
                                $class           : 'GitSCM',
                                branches         : [[name: "${params.GitRef}"]],
                                userRemoteConfigs: [[
                                                            refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pull/*',
                                                            url    : 'https://github.com/pingcap/tidb-dashboard.git',
                                                    ]]
                        ]
                        sh 'export PATH=$GOROOT/bin:/usr/local/bin:$PATH:/opt/homebrew/bin; make package'
                        sh """
                            tar -cvzf tidb-dashboard-darwin-arm64.tar.gz -C bin/ tidb-dashboard
                            curl -F builds/tidb-dashboard/${params.ReleaseTag}/darwin-arm64.tar.gz=@tidb-dashboard-darwin-arm64.tar.gz http://fileserver.pingcap.net/upload
                           """
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
                    cloud "kubernetes"
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
                }
            }
            environment { HUB = credentials('harbor-pingcap') }
            steps {
                sh 'set +x; regctl registry login hub.pingcap.net -u $HUB_USR -p $(printenv HUB_PSW)'
                sh "regctl image copy hub.pingcap.net/rc/tidb-dashboard:${ReleaseTag}  hub.pingcap.net/qa/tidb-dashboard:${ReleaseTag}-pre"
            }
        }
        stage("tiup staging"){
            agent{
                kubernetes{
                    yaml tiupYaml
                    defaultContainer 'tiup'
                }
            }
            environment {TIUP_MIRRORS = 'http://172.16.5.139:8988'; TIUPKEY_JSON = credentials('tiup-prod-key') }
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                sh """
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/linux-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/linux-arm64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/darwin-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/darwin-arm64.tar.gz
                   """
                sh """
                    tiup mirror publish tidb-dashboard ${ReleaseTag} linux-amd64.tar.gz tidb-dashboard --os=linux --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} linux-arm64.tar.gz tidb-dashboard --os=linux --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} darwin-amd64.tar.gz tidb-dashboard --os=darwin --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} darwin-arm64.tar.gz tidb-dashboard --os=darwin --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                   """
            }
        }
        stage("publish tiup"){
            agent{
                kubernetes{
                    yaml tiupYaml
                    defaultContainer 'tiup'
                }
            }
            when {expression{params.PushPublic.toBoolean()}}
            environment {TIUP_MIRRORS = 'http://172.16.5.134:8987'; TIUPKEY_JSON = credentials('tiup-prod-key') }
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                sh """
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/linux-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/linux-arm64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/darwin-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/darwin-arm64.tar.gz
                   """
                sh """
                    tiup mirror publish tidb-dashboard ${ReleaseTag} linux-amd64.tar.gz tidb-dashboard --os=linux --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} linux-arm64.tar.gz tidb-dashboard --os=linux --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} darwin-amd64.tar.gz tidb-dashboard --os=darwin --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} darwin-arm64.tar.gz tidb-dashboard --os=darwin --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                   """
            }
        }
        stage("publish image"){
            agent none
            when {expression{params.PushPublic.toBoolean()}}
            steps{
                build(job: "jenkins-image-syncer",
                    parameters: [
                        string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/rc/tidb-dashboard:${ReleaseTag}"),
                        string(name: 'TARGET_IMAGE', value: "pingcap/tidb-dashboard:${ReleaseTag}"),
                    ],
                    wait: true)
            }
        }
    }
}
