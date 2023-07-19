final specRef = "+refs/heads/*:refs/remotes/origin/*"

final cacheEnableCmd = '''
mkdir -p build
cp -r release-centos7-llvm/scripts build/
ccache -z
sed -i  '/-GNinja/i  \\ \\ -DUSE_INTERNAL_TIFLASH_PROXY=0 \\\\\\n\\ \\ -DPREBUILT_LIBS_ROOT=contrib/tiflash-proxy/ \\\\' build/scripts/build-tiflash-release.sh
export PATH=/lib64/ccache:$PATH
build/scripts/build-release.sh
ccache -s
'''

final cleanBuildCmd = '''release-centos7-llvm/scripts/build-release.sh'''

final cacheMacBuildCmd = '''
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE="RELWITHDEBINFO" -DUSE_INTERNAL_SSL_LIBRARY=ON -DUSE_INTERNAL_TIFLASH_PROXY=0 -DPREBUILT_LIBS_ROOT=contrib/tiflash-proxy/ -Wno-dev -DNO_WERROR=ON -GNinja
cmake  --build . --target tiflash
cd .. && mkdir output && cd output
cmake --install build --component=tiflash-release --prefix="output"
'''

final cleanMacBuildCmd = '''
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE="RELWITHDEBINFO" -DUSE_INTERNAL_SSL_LIBRARY=ON -Wno-dev -DNO_WERROR=ON -GNinja
cmake  --build . --target tiflash
cd .. && mkdir output && cd output
cmake --install build --component=tiflash-release --prefix="output"
'''

String BUILD_CMD

def getBinDownloadURL={
    return "${FILE_SERVER_URL}/download/${BinPath}"
}

def doBuild = {
        stage("checkout"){
            sh """
            if curl --output /dev/null --silent --head --fail ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tics.tar.gz; then
                echo 'Downloading git repo from fileserver...'
                wget -c -O src-tiflash.tar.gz --tries 3 --no-verbose ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tics.tar.gz
                tar -xzf src-tiflash.tar.gz --strip-components=1
                rm -f src-tiflash.tar.gz
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
                                        url          : "git@github.com:pingcap/tiflash.git"]]
                    ]
            sh 'test -z "$(git status --porcelain)"'
            }
        }
        def proxy_cache_path = ""
        def need_update_proxy = false
        stage("fetch cache"){
            if (params.CleanBuild.toBoolean()){
                echo "skip fetch cache because of argument"
            }else{
                def proxy_commit_hash = ""
                dir("contrib/tiflash-proxy") {
                    proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                }
                proxy_cache_path = "builds/cache/tiflash-proxy/$proxy_commit_hash/tiflash-proxy-$OS-$ARCH-llvm-release.tar.gz"
                try{
                    sh """
                    curl --fail -o tiflash_proxy.tar.gz ${FILE_SERVER_URL}/download/$proxy_cache_path
                    mkdir -p contrib/tiflash-proxy/target/release
                    tar -C contrib/tiflash-proxy/target/release -xzvf tiflash_proxy.tar.gz
                    rm -f tiflash_proxy.tar.gz
                    """
                }catch (err) {
                    echo "Caught: ${err}"
                    echo "Start clean build"
                    BUILD_CMD = cleanBuildCmd
                    need_update_proxy = true
                }}
        }
        stage("build"){
            sh """
                for a in \$(git tag --contains ${GitHash}); do echo \$a && git tag -d \$a;done
                git tag -f ${Version} ${GitHash}
                git branch -D refs/tags/${Version} || true
                git checkout -b refs/tags/${Version}
            """
            sh BUILD_CMD
            sh """
                mkdir output
                mv release-centos7-llvm/tiflash output
                cd output
                tar --exclude=tiflash.tar.gz -czvf tiflash.tar.gz tiflash
                sha256sum tiflash.tar.gz | cut -d ' ' -f 1 >tiflash.tar.gz.sha256
            """
        }
        stage("upload"){
        dir("output"){
            sh "curl -F $BinPath=@tiflash.tar.gz ${FILE_SERVER_URL}/upload"
            sh "curl -F ${BinPath}.sha256=@tiflash.tar.gz.sha256 ${FILE_SERVER_URL}/upload"
        }
        }
        stage("upload proxy cache"){
            if(need_update_proxy && proxy_cache_path){
                sh """
                tar -czvf tiflash_proxy.tar.gz -C output/tiflash libtiflash_proxy.so
                curl -F $proxy_cache_path=@tiflash_proxy.tar.gz ${FILE_SERVER_URL}/upload
                """
            }else{
                echo "skip"
            }
        }
}



def buildBin={
    def skip = false
    stage("check"){
        def binExist = sh(script: "curl -I ${getBinDownloadURL()}|grep \"200 OK\"", returnStatus: true)
        if (binExist  == 0) {
            skip = true
        }
    }
    if (skip) {
        echo "Binary exists, skip build"
        return
    }
    dir('tiflash'){
        doBuild()
    }
}


def buildDocker={
    sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
    sh """
        curl --fail --retry 3 -o Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/tiflash.Dockerfile
        curl --fail --retry 3 -o tiflash.tar.gz ${getBinDownloadURL()}
        tar -xzvf tiflash.tar.gz
        rm -f tiflash.tar.gz
        docker build -t ${DockerImage}-$ARCH .
        docker push ${DockerImage}-$ARCH
    """ 
}


pipeline{
    parameters {
        string(name: 'GitHash', description: 'the git tag or commit', defaultValue:'7bd02a86e08f5077c69299ac048a700fcb68507f')
        string(name: 'Version', description: 'important, the Version for cli --Version and profile choosing, eg. v6.5.0', defaultValue:'v7.3.0')
        choice(name: 'Edition', choices : ["community", "enterprise"])
        /*string(name: 'PlatformLinuxAmd64', defaultValue: '', description: 'build path linux amd64')
        string(name: 'PlatformLinuxArm64', defaultValue: '', description: 'build path linux arm64')
        string(name: 'PlatformDarwinAmd64', defaultValue: '', description: 'build path darwin amd64')
        string(name: 'PlatformDarwinArm64', defaultValue: '', description: 'build path darwin arm64')
        */
        booleanParam(name: 'CleanBuild', description:"disable all caches")
        string(name: 'DockerImage', description: 'docker image path', defaultValue: '')
    }
    agent none
    stages{
        stage('Prepare'){
            steps{
                script{
                    if (params.CleanBuild.toBoolean()){
                        BUILD_CMD = cleanBuildCmd
                    }else{
                        BUILD_CMD = cacheEnableCmd
                    }
                    echo "tiflash will build with $BUILD_CMD"
                }
            }
        }
        stage("multi-platform bin"){
        parallel{
            stage("linux/amd64"){
                when {
                    expression{true}
                    beforeAgent true
                }
                agent { kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
metadata:
  namespace: lijie
spec:
  restartPolicy: Never
  containers:
  - name: builder
    image: hub.pingcap.net/lijie/tiflash-builder:amd64
    volumeMounts:
    - name: ccache
      mountPath: "/var/cache/ccache"
    - name: cargohome
      mountPath: "/var/cache/cargohome"
    - name: rustuphome
      mountPath: "/var/cache/rustuphome"
    env:
    - name: CARGO_HOME
      value: '/var/cache/cargohome'
    - name: RUSTUP_HOME
      value: '/var/cache/rustuphome'
    - name: CCACHE_DIR
      value: "/var/cache/ccache"
    - name: CCACHE_TEMPDIR
      value: "/var/tmp/ccache"
    command: ['sleep', '3600000']
    resources:
      requests:
        memory: "32Gi"
        cpu: "16"
      limits:
        memory: "32Gi"
        cpu: "16"
  volumes:
  - name: ccache
    persistentVolumeClaim:
      claimName: tiflash-ccache
  - name: cargohome
    persistentVolumeClaim:
      claimName: tiflash-cargo-home
  - name: rustuphome
    persistentVolumeClaim:
      claimName: tiflash-rustup-home
'''
                defaultContainer 'builder'
            } }
                environment {
                    OS = "linux"
                    ARCH = "amd64"
                    BinPath = "tmp/tiflash.tar.gz"
                }
                steps{
                    script{
                        buildBin()
                    }
                }
            }
            stage("linux/arm64"){
                when {
                    expression {false}
                    beforeAgent true
                }
                agent { kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
metadata:
  namespace: lijie
spec:
  restartPolicy: Never
  containers:
  - name: builder
    image: hub.pingcap.net/tiflash/tiflash-builder:arm64
    volumeMounts:
    - name: ccache
      mountPath: "/var/cache/ccache"
    - name: cargohome
      mountPath: "/var/cache/cargohome"
    - name: rustuphome
      mountPath: "/var/cache/rustuphome"
    env:
    - name: CARGO_HOME
      value: '/var/cache/cargohome'
    - name: RUSTUP_HOME
      value: '/var/cache/rustuphome'
    - name: CMAKE_CXX_COMPILER_LAUNCHER
      value: 'ccache'
    - name: CMAKE_C_COMPILER_LAUNCHER
      value: 'ccache'
    - name: CCACHE_DIR
      value: "/var/cache/ccache"
    - name: CCACHE_TEMPDIR
      value: "/var/tmp/ccache"
    command: ['sleep', '3600000']
    resources:
      requests:
        memory: "32Gi"
        cpu: "16"
      limits:
        memory: "32Gi"
        cpu: "16"
  volumes:
  - name: ccache
    persistentVolumeClaim:
      claimName: tiflash-ccache
  - name: cargohome
    persistentVolumeClaim:
      claimName: tiflash-cargo-home
  - name: rustuphome
    persistentVolumeClaim:
      claimName: tiflash-rustup-home
'''
                defaultContainer 'builder'
                cloud 'kubernetes-arm64'
            } }
                environment {
                    OS = "linux"
                    ARCH = "arm64"
                    BinPath = "${params.PlatformLinuxArm64}"
                }
                steps{
                    script{ 
                        buildBin()
                    }
                }
            }
            stage("darwin/amd64"){
                when {
                    beforeAgent true
                    allOf{
                        equals expected: "community", actual: params.Edition 
                        expression{false}
                    }
                }
                agent { node { label 'darwin && amd64' } }
                environment {
                    OS = "darwin"
                    ARCH = "amd64"
                    BinPath = "${params.PlatformDarwinAmd64}"
                    PATH = "/usr/local/go1.20.3/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                }
                steps{
                    script{
                        buildBin()
                    }
                }
            }
            stage("darwin/arm64"){
                when {
                    beforeAgent true
                    allOf{
                        equals expected: "community", actual: params.Edition 
                        expression{false}
                    }
                }
                agent { node { label 'darwin && arm64' } }
                environment {
                    OS = "darwin"
                    ARCH = "arm64"
                    BinPath = "${params.PlatformDarwinArm64}"
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
            when {
                not{equals expected: "", actual: params.DockerImage}
                beforeAgent true
            }
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
            when {
                not{equals expected: "", actual: params.DockerImage}
                beforeAgent true
            }
            agent { node { label 'arm' } }
            environment {
                HUB = credentials('harbor-pingcap') 
            }
            steps {
                script{
                    def ammend = ""
                    if (params.PlatformLinuxAmd64.toBoolean()){
                        ammend += " -a ${DockerImage}-amd64"
                    }
                    if (params.PlatformLinuxArm64.toBoolean()){
                        ammend += " -a ${DockerImage}-arm64"
                    }
                    if (ammend == ""){
                        return
                    }
                    sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
                    sh """docker manifest create ${DockerImage} ${ammend}
                          docker manifest push ${DockerImage}
                    """
                }
            }
        }
    }
}
