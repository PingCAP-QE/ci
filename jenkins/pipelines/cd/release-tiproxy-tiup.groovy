final proxy_desc = "tiproxy is a database proxy that is based on TiDB."
final gitrepo = "https://github.com/pingcap/tiproxy.git"
final cmd = """go version
make cmd
"""

pipeline{
    environment {
        GOPROXY = "http://goproxy.pingcap.net,https://proxy.golang.org,direct"
    }
    agent none
    stages{
        stage("build"){
            parallel{
                stage("linux/amd64"){
                    agent{
                        kubernetes{
                            yaml '''
kind: Pod
spec:
  containers:
  - name: golang
    image: hub.pingcap.net/jenkins/centos7_golang-1.21
    args: ["sleep", "infinity"]
  nodeSelector:
    kubernetes.io/arch: amd64
'''
                            defaultContainer 'golang'
                        }
                    }
                    steps{
                        checkout([$class: 'GitSCM',
                            branches: [[name: "${params.GitRef}"]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[url: gitrepo]]]
                        )
                        sh cmd
                        sh "tar -C bin/ -czvf tiproxy-linux-amd64.tar.gz tiproxy"
                        stash includes: "tiproxy-linux-amd64.tar.gz", name: "tiproxy-linux-amd64.tar.gz"
                    }
                }
                stage("linux/arm64"){
                    agent{
                        kubernetes{
                            yaml '''
kind: Pod
spec:
  containers:
  - name: golang
    image: hub.pingcap.net/jenkins/centos7_golang-1.21-arm64
    args: ["sleep", "infinity"]
  nodeSelector:
    kubernetes.io/arch: arm64
'''
                            defaultContainer 'golang'
                        }
                    }
                    steps{
                        checkout([$class: 'GitSCM',
                            branches: [[name: "${params.GitRef}"]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[url: gitrepo]]]
                        )
                        sh cmd
                        sh "tar -C bin/ -czvf tiproxy-linux-arm64.tar.gz tiproxy"
                        stash includes: "tiproxy-linux-arm64.tar.gz", name: "tiproxy-linux-arm64.tar.gz"
                    }
                }
                stage("darwin/amd64"){
                    agent{
                        label "darwin && amd64"
                    }
                    environment {
                        GOROOT = '/usr/local/go'
                        PATH="$GOROOT/bin:/usr/local/bin:/bin:/usr/bin:/opt/homebrew/bin"
                    }
                    steps{
                        checkout([$class: 'GitSCM',
                            branches: [[name: "${params.GitRef}"]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[url: gitrepo]]]
                        )
                        sh cmd
                        sh "tar -C bin/ -czvf tiproxy-darwin-amd64.tar.gz tiproxy"
                        stash includes: "tiproxy-darwin-amd64.tar.gz", name: "tiproxy-darwin-amd64.tar.gz"
                    }
                }
                stage("darwin/arm64"){
                    agent{
                        label "darwin && arm64"
                    }
                    environment {
                        GOROOT = '/usr/local/go'
                        PATH="$GOROOT/bin:/usr/local/bin:/bin:/usr/bin:/opt/homebrew/bin"
                    }
                    steps{
                        checkout([$class: 'GitSCM',
                            branches: [[name: "${params.GitRef}"]],
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[url: gitrepo]]]
                        )
                        sh cmd
                        sh "tar -C bin/ -czvf tiproxy-darwin-arm64.tar.gz tiproxy"
                        stash includes: "tiproxy-darwin-arm64.tar.gz", name: "tiproxy-darwin-arm64.tar.gz"
                    }
                }
            }
        }
        stage("publish tiup"){
            agent{
                kubernetes{
                    yaml '''
spec:
  containers:
  - name: tiup
    image: hub.pingcap.net/jenkins/tiup
    args: ["sleep", "infinity"]
'''
                    defaultContainer 'tiup'
                }
            }
            environment {
                TIUPKEY_JSON = credentials('tiup-prod-key')
            }
            stages{
                stage("prepare"){
                    steps{
                        sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                    }
                }
                stage("tiup staging"){
                    when {expression{params.TiupStaging.toBoolean()}}
                    environment { TIUP_MIRRORS = "http://tiup.pingcap.net:8988" }
                    matrix{
                        axes {
                            axis {
                                name 'OS'
                                values  "linux", "darwin"
                            }
                            axis {
                                name 'ARCH'
                                values  "amd64", "arm64"
                            }
                        }
                        stages {
                            stage("publish") {
                                steps {
                                    unstash "tiproxy-$OS-${ARCH}.tar.gz"
                                    sh """echo tiup mirror publish tiproxy ${params.Version} tiproxy-$OS-${ARCH}.tar.gz tiproxy  --os=$OS --arch=${ARCH} --desc="${proxy_desc}" """
                                }
                            }
                        }
                    }
                }
                stage("tiup product"){
                    when {expression{params.TiupProduct.toBoolean()}}
                    environment { TIUP_MIRRORS = "http://tiup.pingcap.net:8987" }
                    matrix{
                        axes {
                            axis {
                                name 'OS'
                                values  "linux", "darwin"
                            }
                            axis {
                                name 'ARCH'
                                values  "amd64", "arm64"
                            }
                        }
                        stages {
                            stage("publish") {
                                steps {
                                    unstash "tiproxy-$OS-${ARCH}.tar.gz"
                                    sh """echo tiup mirror publish tiproxy ${params.Version} tiproxy-$OS-${ARCH}.tar.gz tiproxy  --os=$OS --arch=${ARCH} --desc="${proxy_desc}" """
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
