final specRef = "+refs/heads/*:refs/remotes/origin/*"
String BUILD_CMD

def getAgentBlock={OS, ARCH ->
    switch("$OS/$ARCH"){
    case "darwin/amd64":
        return { node { label 'darwin&&amd64' } }
    case "darwin/arm64":
        return { node { label 'darwin&&arm64' } }
    case "linux/amd64":
        return { node { label 'build_go1200' } }
    case "linux/arm64":
        return { kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: builder
    image: "hub.pingcap.net/jenkins/centos7_golang-1.20-arm64:latest"
    args: ["sleep", "infinity"]
    resources:
      requests:
        memory: "8Gi"
        cpu: "4"
      limits:
        memory: "8Gi"
        cpu: "4"
'''
                defaultContainer 'builder'
            } }
        break
    default:
        error "Unsupported platform: ${agentLabel}"
    }
}

def build ={
    stages{
                stage('Checkout'){
                    steps{
                        sh """
                        if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz; then
                            echo 'Downloading git repo from fileserver...'
                            wget -c --tries 3 --no-verbose ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz
                            tar -xzf src-tidb.tar.gz --strip-components=1
                            rm -f src-tidb.tar.gz
                            rm -rf ./
                        else 
                            exit 1; 
                        fi
                        """
                        retry(3) { 
                            checkout changelog: false, poll: true,
                            scm: [$class: 'GitSCM', branches: [[name: "${GitHash}"]], doGenerateSubmoduleConfigurations: false,
                                extensions: [[$class: 'CheckoutOption', timeout: 30],
                                            [$class: 'CloneOption', timeout: 60],
                                            [$class: 'PruneStaleBranch'],
                                            [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                            [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                                userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                    refspec      : specRef,
                                                    url          : repo]]]
                        sh 'test -z "$(git status --porcelain)"'
                        }
                    }
                }
                stage('BUILD'){
                    environment {
                        GOPROXY = "http://goproxy.pingcap.net,https://proxy.golang.org,direct" 
                    }
                    steps{
                        sh """
                            for a in \$(git tag --contains ${GitHash}); do echo \$a && git tag -d \$a;done
                            git tag -f ${Version} ${GitHash}
                            git branch -D refs/tags/${Version} || true
                            git checkout -b refs/tags/${Version}
                        """
                        sh "go env"
                        sh BUILD_CMD
                        sh """
                            rm -rf output
                            mkdir -p output/bin
                            cp bin/* output/bin/ 
                            tar -czvf tidb.tar.gz . -C output/bin/
                            sha256sum tidb.tar.gz | cut -d ' ' -f 1 >tidb.tar.gz.sha256
                        """
                    }
                }
                stage('Upload'){
                    steps{
                        script{
                            def BinPath = sprintf("BinPathPattern", Version, OS, ARCH, GitHash)
                            sh "curl -F $BinPath=@tidb.tar.gz ${FILE_SERVER_URL}/upload"
                            sh "curl -F ${BinPath}.sha256=@tidb.tar.gz.sha256 ${FILE_SERVER_URL}/upload"
                        }
                    }
                }
                }
}


pipeline{
    parameters {
        string(name: 'GitHash', description: 'the git tag or commit or branch or pull/id of repo')
        string(name: 'Version', description: 'important, the Version for cli --Version and profile choosing, eg. v6.5.0')
        choice(name: 'Edition', choices : ["community", "enterprise"])
        string(name: 'BuildCmd', description: 'the build command', defaultValue: '')
        string(name: 'BinPathPattern', description: 'the fileserver binary path', defaultValue: '')
        string(name: 'DockerImage', description: 'the fileserver binary path', defaultValue: '')
    }
    agent none
    stages{
        stage('Prepare'){
            steps{
                script{
                    if (params.EDITION == "enterprise") {
                        BUILD_CMD = "make enterprise"
                    }else{
                        BUILD_CMD = "make"
                    }
                    if (BuildCmd != "") {
                        BUILD_CMD = params.BuildCmd
                    }
                    echo "tidb will build with $BUILD_CMD"
                }
            }
        }
        
        stage("Multi-Arch Build"){
            matrix{
                axes{
                    axis{
                        name "OS"
                        values "linux", "darwin"
                    }
                    axis{
                        name "ARCH"
                        values "amd64", "arm64"
                    }
                }
            stages{
                stage("Build"){
                    steps{
                        script{
                            build()
                        }
                    }
                }
            }
        }
        }
    }
}