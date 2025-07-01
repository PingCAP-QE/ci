package cd

def dockerfile = '''
FROM ghcr.io/pingcap-qe/cd/builders/tidb-dashboard:v20240325-28-g64610d1 as builder

# Build.
WORKDIR /go/src/github.com/pingcap/tidb-dashboard
COPY . .
RUN ${params.BuildEnv} make package

FROM hub.pingcap.net/bases/pingcap-base:v1

RUN dnf install perl-interpreter -y && dnf clean all

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

def BinaryPrefix = "builds/tidb-dashboard/${params.ReleaseTag}"
def DockerImg = "hub.pingcap.net/rc/tidb-dashboard:${params.ReleaseTag}"

pipeline {
    agent none
    stages {
        stage("prepare"){
            steps{
                script{
                    def env = ""
                    if (params.BuildEnv){
                        env = "${params.BuildEnv}"
                    }
                    dockerfile = dockerfile.replace('${params.BuildEnv}', env.toString())
                    if (params.DockerImg){
                        DockerImg = params.DockerImg
                    }
                }
            }
        }
        stage("build multi-arch"){
            parallel{
                stage("linux/amd64"){
                    options { retry(3) }
                    environment { HUB = credentials('harbor-pingcap') }
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'builder'
                            cloud "kubernetes"
                            nodeSelector "kubernetes.io/arch=amd64"
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
                        writeFile file: 'buildkitd.toml', text: '''[registry."docker.io"]\nmirrors = ["registry-mirror.pingcap.net"]'''
                        sh 'docker buildx create --name mybuilder --use  --config buildkitd.toml && rm buildkitd.toml'
                        writeFile file:'build-tidb-dashboard.Dockerfile',text:dockerfile
                        sh 'cat build-tidb-dashboard.Dockerfile'
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t hub.pingcap.net/rc/tidb-dashboard-builder:cache-amd64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-amd64 --target builder '--platform=linux/amd64' --build-arg 'BUILDKIT_INLINE_CACHE=1' --push"
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t ${DockerImg}-amd64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-amd64 --push --platform=linux/amd64 --build-arg BUILDKIT_INLINE_CACHE=1 --progress=plain --provenance=false"
                        sh """
                    docker pull ${DockerImg}-amd64
                    docker run --platform=linux/amd64 --name=amd64 --entrypoint=/bin/cat  ${DockerImg}-amd64
                    mkdir -p bin/linux-amd64/
                    docker cp amd64:/tidb-dashboard bin/linux-amd64/tidb-dashboard
                    docker stop amd64
                    docker container prune -f
                """
                        container("uploader") {
                            sh """
                            tar -cvzf tidb-dashboard-linux-amd64.tar.gz -C bin/linux-amd64 tidb-dashboard
                            sha256sum tidb-dashboard-linux-amd64.tar.gz | cut -d ' ' -f 1 >tidb-dashboard-linux-amd64.tar.gz.sha256
                            curl -F ${BinaryPrefix}/tidb-dashboard-linux-amd64.tar.gz=@tidb-dashboard-linux-amd64.tar.gz http://fileserver.pingcap.net/upload
                            curl -F ${BinaryPrefix}/tidb-dashboard-linux-amd64.tar.gz.sha256=@tidb-dashboard-linux-amd64.tar.gz.sha256 http://fileserver.pingcap.net/upload
                            """
                        }
                    }
                }
                stage("linux/arm64"){
                    options { retry(3) }
                    environment { HUB = credentials('harbor-pingcap') }
                    agent {
                        kubernetes {
                            yaml podYaml
                            defaultContainer 'builder'
                            cloud "kubernetes"
                            nodeSelector "kubernetes.io/arch=arm64"
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
                        writeFile file: 'buildkitd.toml', text: '''[registry."docker.io"]\nmirrors = ["registry-mirror.pingcap.net"]'''
                        sh 'docker buildx create --name mybuilder --use  --config buildkitd.toml && rm buildkitd.toml'
                        writeFile file:'build-tidb-dashboard.Dockerfile',text:dockerfile
                        sh 'cat build-tidb-dashboard.Dockerfile'
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t hub.pingcap.net/rc/tidb-dashboard-builder:cache-arm64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-arm64 --target builder '--platform=linux/arm64' --build-arg 'BUILDKIT_INLINE_CACHE=1' --push"
                        sh "docker buildx build . -f build-tidb-dashboard.Dockerfile -t ${DockerImg}-arm64 --cache-from hub.pingcap.net/rc/tidb-dashboard-builder:cache-arm64 --push --platform=linux/arm64 --build-arg BUILDKIT_INLINE_CACHE=1 --progress=plain --provenance=false"
                        sh """
                            docker pull ${DockerImg}-arm64
                            docker run --platform=linux/arm64 --name=arm64 --entrypoint=/bin/cat  ${DockerImg}-arm64
                            mkdir -p bin/linux-arm64/
                            docker cp arm64:/tidb-dashboard bin/linux-arm64/tidb-dashboard
                            docker stop arm64
                            docker container prune -f
                           """
                        container("uploader") {
                            sh """
                            tar -cvzf tidb-dashboard-linux-arm64.tar.gz -C bin/linux-arm64 tidb-dashboard
                            sha256sum tidb-dashboard-linux-arm64.tar.gz | cut -d ' ' -f 1 >tidb-dashboard-linux-arm64.tar.gz.sha256
                            curl -F ${BinaryPrefix}/tidb-dashboard-linux-arm64.tar.gz=@tidb-dashboard-linux-arm64.tar.gz http://fileserver.pingcap.net/upload
                            curl -F ${BinaryPrefix}/tidb-dashboard-linux-arm64.tar.gz.sha256=@tidb-dashboard-linux-arm64.tar.gz.sha256 http://fileserver.pingcap.net/upload
                            """
                        }
                    }
                }
                stage("darwin/amd64"){
                    when {
                        beforeAgent true
                        equals expected:false, actual:params.IsDevbuild.toBoolean()
                    }
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
                            sha256sum tidb-dashboard-darwin-amd64.tar.gz | cut -d ' ' -f 1 >tidb-dashboard-darwin-amd64.tar.gz.sha256
                            curl -F ${BinaryPrefix}/tidb-dashboard-darwin-amd64.tar.gz=@tidb-dashboard-darwin-amd64.tar.gz http://fileserver.pingcap.net/upload
                            curl -F ${BinaryPrefix}/tidb-dashboard-darwin-amd64.tar.gz.sha256=@tidb-dashboard-darwin-amd64.tar.gz.sha256 http://fileserver.pingcap.net/upload
                           """
                    }
                }
                stage("darwin/arm64"){
                    when {
                        beforeAgent true
                        equals expected:false, actual:params.IsDevbuild.toBoolean()
                    }
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
                            sha256sum tidb-dashboard-darwin-arm64.tar.gz | cut -d ' ' -f 1 >tidb-dashboard-darwin-arm64.tar.gz.sha256
                            curl -F ${BinaryPrefix}/tidb-dashboard-darwin-arm64.tar.gz=@tidb-dashboard-darwin-arm64.tar.gz http://fileserver.pingcap.net/upload
                            curl -F ${BinaryPrefix}/tidb-dashboard-darwin-arm64.tar.gz.sha256=@tidb-dashboard-darwin-arm64.tar.gz.sha256 http://fileserver.pingcap.net/upload
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
                sh """docker manifest create ${DockerImg} -a ${DockerImg}-amd64 ${DockerImg}-arm64
                      docker manifest push ${DockerImg}
                """
            }
        }
        stage("sync to qa"){
            when {
                beforeAgent true
                equals expected:false, actual:params.IsDevbuild.toBoolean()
            }
            agent {
                kubernetes {
                    yaml dockerSyncYaml
                    defaultContainer 'regctl'
                }
            }
            environment { HUB = credentials('harbor-pingcap') }
            steps {
                sh 'set +x; regctl registry login hub.pingcap.net -u $HUB_USR -p $(printenv HUB_PSW)'
                sh "regctl image copy ${DockerImg}  hub.pingcap.net/qa/tidb-dashboard:${ReleaseTag}-pre"
            }
        }
        stage("tiup staging"){
            when {
                beforeAgent true
                equals expected:false, actual:params.IsDevbuild.toBoolean()
            }
            agent {
                kubernetes{
                    yaml tiupYaml
                    defaultContainer 'tiup'
                }
            }
            environment {TIUP_MIRRORS = 'http://tiup.pingcap.net:8988'; TIUPKEY_JSON = credentials('tiup-prod-key') }
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                sh """
                    curl -O http://fileserver.pingcap.net/download/${BinaryPrefix}/tidb-dashboard-linux-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/${BinaryPrefix}/tidb-dashboard-linux-arm64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/${BinaryPrefix}/tidb-dashboard-darwin-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/${BinaryPrefix}/tidb-dashboard-darwin-arm64.tar.gz
                   """
                sh """
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-linux-amd64.tar.gz tidb-dashboard --os=linux --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-linux-arm64.tar.gz tidb-dashboard --os=linux --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-darwin-amd64.tar.gz tidb-dashboard --os=darwin --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-darwin-arm64.tar.gz tidb-dashboard --os=darwin --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                   """
            }
        }
    }
}
