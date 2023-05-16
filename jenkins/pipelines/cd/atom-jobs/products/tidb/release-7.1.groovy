final specRef = "+refs/heads/*:refs/remotes/origin/*"
String BUILD_CMD

def getBinPath={
    return "builds/devbuild/tidb/optimization/${params.Version}/${params.GitHash}/tidb-${OS}-${ARCH}.tar.gz"
}

def getBinDownloadURL={
    return "${FILE_SERVER_URL}/download/${getBinPath()}"
}

def getPluginPath={
    return "builds/devbuild/tidb/optimization/${params.Version}/${params.GitHash}/tidb-plugin-${OS}-${ARCH}.tar.gz"
}
def getPluginDownloadURL={
    return "${FILE_SERVER_URL}/download/${getPluginPath()}"
}

def buildTidb = {
        stage("checkout"){
            sh """
            if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz; then
                echo 'Downloading git repo from fileserver...'
                wget -c --tries 3 --no-verbose ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz
                tar -xzf src-tidb.tar.gz --strip-components=1
                rm -f src-tidb.tar.gz
                rm -rf ./*
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
                                        url          : "git@github.com:pingcap/tidb.git"]]
                    ]
            sh 'test -z "$(git status --porcelain)"'
            }
        }
        stage("build"){
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
                mkdir -p output
                cp bin/* output/
                tar -czvf tidb.tar.gz -C output/ . 
                sha256sum tidb.tar.gz | cut -d ' ' -f 1 >tidb.tar.gz.sha256
            """
        }
        stage("upload"){
            def BinPath = getBinPath()
            sh "curl -F $BinPath=@tidb.tar.gz ${FILE_SERVER_URL}/upload"
            sh "curl -F ${BinPath}.sha256=@tidb.tar.gz.sha256 ${FILE_SERVER_URL}/upload"
        }
}

def buildEnterprisePlugin = {
    stage("build enterprise plugin"){
        checkout changelog: false, poll: true,
                        scm: [$class: 'GitSCM', branches: [[name: "${PluginGitHash}"]], doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'CheckoutOption', timeout: 30],
                                        [$class: 'CloneOption', timeout: 60],
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                        [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                refspec      : specRef,
                                                url          : "git@github.com:pingcap/enterprise-plugin.git"]]]
        sh """
        go version
        cd ../tidb/cmd/pluginpkg
        go build 
        cd ../../../enterprise-plugin
        cd whitelist
        go mod tidy
        cd ..
        ../tidb/cmd/pluginpkg/pluginpkg -pkg-dir whitelist -out-dir whitelist
        md5sum whitelist/whitelist-1.so > whitelist/whitelist-1.so.md5
        cd audit
        go mod tidy
        cd ..
        ../tidb/cmd/pluginpkg/pluginpkg -pkg-dir audit -out-dir audit
        md5sum audit/audit-1.so > audit/audit-1.so.md5
        """ 
        sh """
        rm -rf output
        mkdir output
        cp whitelist/whitelist-1.so.md5 output
        cp whitelist/whitelist-1.so output
        cp audit/audit-1.so.md5 output
        cp audit/audit-1.so output
        tar -czvf enterpise-plugin.tar.gz -C output/ . 
        sha256sum  enterpise-plugin.tar.gz | cut -d ' ' -f 1 > enterpise-plugin.tar.gz.sha256
        """
        sh "curl -F $PluginBinPath=@enterprise-plugin.tar.gz ${FILE_SERVER_URL}/upload"
        sh "curl -F ${PluginBinPath}.sha256=@enterprise-plugin.tar.gz.sha256 ${FILE_SERVER_URL}/upload"
    }
}


def buildBin={
    def skip = false
    stage("check"){
        def binExist = sh(script: "curl -I ${getBinDownloadURL()}|grep \"200 OK\"", returnStatus: true)
        if (params.Edition == "enterprise") {
            def pluginExist = sh(script: "curl -I ${getPluginDownloadURL()}|grep \"200 OK\"", returnStatus: true)
            if (binExist  == 0 && pluginExist == 0) {
                skip = true
            }
        }else{
            if (binExist  == 0) {
                skip = true
            }
        }
    }
    if (skip) {
        echo "Binary exists, skip build"
        return
    }
    dir('tidb'){
        buildTidb()
    }
    if (params.Edition == "enterprise") {
        dir('enterprise-plugin'){
            buildEnterprisePlugin()
        }
    }
}


def buildDocker={
    sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
    if (params.Edition == "enterprise"){
        sh """
            curl --fail --retry 3 -o Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/tidb-enterprise.Dockerfile
            curl --fail --retry 3 -o tidb.tar.gz ${getBinDownloadURL()}
            curl --fail --retry 3 -o plugin.tar.gz ${getPluginDownloadURL()}
            tar -xzvf tidb.tar.gz
            tar -xzvf plugin.tar.gz
            rm -f tidb.tar.gz
            rm -f plugin.tar.gz
            docker build -t ${DockerImage}-$ARCH .
            docker push ${DockerImage}-$ARCH
        """ 
    }else{
        sh """
            curl --fail --retry 3 -o Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/tidb.Dockerfile
            curl --fail --retry 3 -o tidb.tar.gz ${getBinDownloadURL()}
            tar -xzvf tidb.tar.gz
            rm -f tidb.tar.gz
            docker build -t ${DockerImage}-$ARCH .
            docker push ${DockerImage}-$ARCH
        """ 
    }
}


pipeline{
    parameters {
        string(name: 'GitHash', description: 'the git tag or commit or branch or pull/id of repo')
        string(name: 'PluginGitHash', description: 'plugin git hash')
        string(name: 'Version', description: 'important, the Version for cli --Version and profile choosing, eg. v6.5.0')
        choice(name: 'Edition', choices : ["community", "enterprise"])
        string(name: 'BuildCmd', description: 'the build command', defaultValue: '')
        string(name: 'DockerImage', description: 'the fileserver binary path', defaultValue: '')
    }
    agent none
    stages{
        stage('Prepare'){
            steps{
                script{
                    if (params.Edition == "enterprise") {
                        BUILD_CMD = "make enterprise-prepare enterprise-server-build"
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
        stage("multi-platform bin"){
        parallel{
            stage("linux/amd64"){
                agent { node { label 'build_go1200' } }
                environment {
                    OS = "linux"
                    ARCH = "amd64"
                }
                steps{
                    script{ container('golang'){
                    }}
                }
            }
            stage("linux/arm64"){
                agent { kubernetes {
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
                cloud 'kubernetes-arm64'
            } }
                environment {
                    OS = "linux"
                    ARCH = "arm64"
                }
                steps{
                    script{ 
                        dir('tidb'){
                            buildBin()
                        }
                    }
                }
            }
            stage("darwin/amd64"){
                when { beforeAgent true; equals expected: "community", actual: params.Edition }
                agent { node { label 'darwin && amd64' } }
                environment {
                    OS = "darwin"
                    ARCH = "amd64"
                    PATH = "/usr/local/go1.20.3/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                }
                steps{
                    script{
                        buildBin()
                    }
                }
            }
            stage("darwin/arm64"){
                when { beforeAgent true; equals expected: "community", actual: params.Edition }
                agent { node { label 'darwin && arm64' } }
                environment {
                    OS = "darwin"
                    ARCH = "arm64"
                    PATH = "/usr/local/go1.20.3/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                }
                steps{
                    script{
                        buildBin()
                    }
                }
            }
        }
        }
        stage("multi-arch docker"){
            parallel{
                stage("amd64"){
                    agent { node { label 'delivery' } }
                    environment {
                        ARCH = "amd64"
                        OS = "linux"
                        HUB = credentials('harbor-pingcap') 
                        DOCKER_HOST = "tcp://localhost:2375"
                    }
                    steps {container('delivery'){script{
                        buildDocker()
                    }}}
                }
                stage("arm64"){
                    agent { node { label 'arm' } }
                    environment {
                        ARCH = "arm64"
                        OS = "linux"
                        HUB = credentials('harbor-pingcap') 
                    }
                    steps {script{
                        buildDocker()
                    }}
                }
            }
        }
        stage("manifest docker image"){
            agent { node { label 'arm' } }
            environment {
                HUB = credentials('harbor-pingcap') 
            }
            steps {
                sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                sh """docker manifest create ${DockerImage} -a ${DockerImage}-amd64 -a ${DockerImage}-arm64
                      docker manifest push ${DockerImage}
                """
            }
        }
    }
}
