final proxy_desc = "tiproxy is a database proxy that is based on TiDB."

def build = {
    checkout([$class: 'GitSCM',
        branches: [[name: "${params.GitRef}"]],
        extensions: [[$class: 'LocalBranch']],
        userRemoteConfigs: [[url: 'https://github.com/pingcap/tiproxy.git']]]
    )
    sh "make cmd"
}

def publish_tiup = {
    sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
    unstash "linux-amd64"
    unstash "linux-arm64"
    unstash "darwin-amd64"
    unstash "darwin-arm64"
    sh """
        tiup mirror publish tiproxy ${params.Version} tiproxy-linux-amd64.tar.gz tiproxy  --os=linux --arch=amd64 --desc="${proxy_desc}"
        tiup mirror publish tiproxy ${params.Version} tiproxy-linux-arm64.tar.gz tiproxy  --os=linux --arch=arm64 --desc="${proxy_desc}"
        tiup mirror publish tiproxy ${params.Version} tiproxy-darwin-amd64.tar.gz tiproxy  --os=darwin --arch=amd64 --desc="${proxy_desc}"
        tiup mirror publish tiproxy ${params.Version} tiproxy-darwin-arm64.tar.gz tiproxy  --os=darwin --arch=arm64 --desc="${proxy_desc}"
        """
}

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
                        script{
                            build()
                            sh "tar -C bin/ -czvf tiproxy-linux-amd64.tar.gz tiproxy"
                        }
                        stash includes: "bin/", name: "linux-amd64"
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
                        script{
                            build()
                            sh "tar -C bin/ -czvf tiproxy-linux-arm64.tar.gz tiproxy"
                        }
                        stash includes: "bin/", name: "linux-arm64"
                    }
                }
                stage("darwin/amd64"){
                    agent{
                        label "darwin && amd64"
                    }
                    environment {
                        GOROOT = '/usr/local/go1.21'
                        PATH="$GOROOT/bin:/usr/local/bin:/bin:/usr/bin:/opt/homebrew/bin"
                    }
                    steps{
                        script{
                            build()
                            sh "tar -C bin/ -czvf tiproxy-darwin-amd64.tar.gz tiproxy"
                        }
                        stash includes: "bin/", name: "darwin-amd64"
                    }
                }
                stage("darwin/arm64"){
                    agent{
                        label "darwin && arm64"
                    }
                    environment {
                        GOROOT = '/usr/local/go1.21'
                        PATH="$GOROOT/bin:/usr/local/bin:/bin:/usr/bin:/opt/homebrew/bin"
                    }
                    steps{
                        script{
                            build()
                            sh "tar -C bin/ -czvf tiproxy-darwin-arm64.tar.gz tiproxy"
                        }
                        stash includes: "bin/", name: "darwin-arm64"
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
                stage("tiup staging"){
                    when {expression{params.TiupStaging.toBoolean()}}
                    environment { TIUP_MIRRORS = "http://tiup.pingcap.net:8988" }
                    steps{
                        script{
                            publish_tiup()
                        }
                    }
                }
                stage("tiup product"){
                    when {expression{params.TiupProduct.toBoolean()}}
                    environment { TIUP_MIRRORS = "http://tiup.pingcap.net:8987" }
                    steps{
                        script{
                            publish_tiup()
                        }
                    }
                }
            }
        }
    }
}
