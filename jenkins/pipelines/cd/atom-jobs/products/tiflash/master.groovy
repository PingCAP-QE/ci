final specRef = "+refs/heads/*:refs/remotes/origin/*"

final cacheLinuxCmd = '''
mkdir -p build
cp -r release-centos7-llvm/scripts build/
sed -i  '/-GNinja/i  \\ \\ -DUSE_INTERNAL_TIFLASH_PROXY=0 \\\\\\n\\ \\ -DPREBUILT_LIBS_ROOT=contrib/tiflash-proxy/ \\\\' build/scripts/build-tiflash-release.sh
export PATH=/usr/lib64/ccache:$PATH
ccache -z
build/scripts/build-release.sh
ccache -s
mkdir output
mv release-centos7-llvm/tiflash output
'''

final cleanLinuxCmd = '''
release-centos7-llvm/scripts/build-release.sh
mkdir output
mv release-centos7-llvm/tiflash output
'''

final cacheMacCmd = '''
export PATH=/usr/local/opt/ccache/libexec:$PATH
ccache -z
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE="RELWITHDEBINFO" -DUSE_INTERNAL_SSL_LIBRARY=ON -DUSE_INTERNAL_TIFLASH_PROXY=0 -DPREBUILT_LIBS_ROOT=contrib/tiflash-proxy/ -Wno-dev -DNO_WERROR=ON
cmake  --build . --target tiflash --parallel $NPROC
cmake --install . --component=tiflash-release --prefix="../output/tiflash"
ccache -s
'''

final cleanMacCmd = '''
mkdir -p build && cd build
cmake .. -DCMAKE_BUILD_TYPE="RELWITHDEBINFO" -DUSE_INTERNAL_SSL_LIBRARY=ON -Wno-dev -DNO_WERROR=ON
cmake  --build . --target tiflash --parallel $NPROC
cmake --install . --component=tiflash-release --prefix="../output/tiflash"
'''

String BUILD_CMD

def getBinDownloadURL={
    return "${FILE_SERVER_URL}/download/${BinPath}"
}

def getBuildCMD = {disableCache ->
    if (BUILD_CMD) {
        return BUILD_CMD
    }
    if (OS=="linux") {
        if(disableCache) {
            return cleanLinuxCmd
        }
        return cacheLinuxCmd
    } else if (OS == "darwin") {
        if(disableCache) {
            return cleanMacCmd
        }
        return cacheMacCmd
    } else {
        throw new Exception("pipeline script error")
    }
}

def dylib_postfix = {
    if (OS=="linux") {
        return "so"
    } else if (OS == "darwin") {
        return "dylib"
    } else {
        throw new Exception("pipeline script error")
    }
}

def doBuild = {
        stage("checkout") {
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
            sh "git config --global --add safe.directory '*'"
            sh 'test -z "$(git status --porcelain)"'
            }
        }
        def proxy_cache_path = ""
        def need_update_proxy = false
        def disableCache = params.CleanBuild.toBoolean()
        stage("fetch cache") {
            if ( disableCache ) {
                echo "skip fetch cache because of argument"
            } else {
                def proxy_commit_hash = ""
                dir("contrib/tiflash-proxy") {
                    proxy_commit_hash = sh(returnStdout: true, script: 'git log -1 --format="%H"').trim()
                }
                proxy_cache_path = "builds/cache/tiflash-proxy/$proxy_commit_hash/tiflash-proxy-$OS-$ARCH-llvm-release.tar.gz"
                try {
                    sh """
                    curl --fail -o tiflash_proxy.tar.gz ${FILE_SERVER_URL}/download/$proxy_cache_path
                    mkdir -p contrib/tiflash-proxy/target/release
                    tar -C contrib/tiflash-proxy/target/release -xzvf tiflash_proxy.tar.gz
                    rm -f tiflash_proxy.tar.gz
                    """
                }catch (err) {
                    echo "Caught: ${err}"
                    echo "Start clean build"
                    disableCache = true
                    need_update_proxy = true
                }}
        }
        stage("build") {
            sh """
                for a in \$(git tag --contains ${GitHash}); do echo \$a && git tag -d \$a;done
                git tag -f ${Version} ${GitHash}
                git branch -D refs/tags/${Version} || true
                git checkout -b refs/tags/${Version}
            """
            sh getBuildCMD(disableCache)
            sh """
                cd output
                tar --exclude=tiflash.tar.gz -czvf tiflash.tar.gz tiflash
                sha256sum tiflash.tar.gz | cut -d ' ' -f 1 >tiflash.tar.gz.sha256
            """
        }
        stage("upload") {
        dir("output") {
            sh "curl -F $BinPath=@tiflash.tar.gz ${FILE_SERVER_URL}/upload"
            sh "curl -F ${BinPath}.sha256=@tiflash.tar.gz.sha256 ${FILE_SERVER_URL}/upload"
        }
        }
        stage("upload proxy cache") {
            if(need_update_proxy && proxy_cache_path) {
                sh """
                tar -czvf tiflash_proxy.tar.gz -C output/tiflash libtiflash_proxy.${dylib_postfix()}
                curl -F $proxy_cache_path=@tiflash_proxy.tar.gz ${FILE_SERVER_URL}/upload
                """
            } else {
                echo "skip"
            }
        }
}



def buildBin={
    def skip = false
    stage("check") {
        def binExist = sh(script: "curl -I ${getBinDownloadURL()}|grep \"200 OK\"", returnStatus: true)
        if (!params.CleanBuild.toBoolean() && binExist  == 0) {
            skip = true
        }
    }
    if (skip) {
        echo "Binary exists, skip build"
        return
    }
    dir('tiflash') {
        doBuild()
    }
}


def buildDocker={
    sh 'printenv HUB_PSW | docker login -u $HUB_USR --password-stdin hub.pingcap.net'
    sh """
        curl --fail --retry 3 -o Dockerfile https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflash/Dockerfile
        curl --fail --retry 3 -o tiflash.tar.gz ${getBinDownloadURL()}
        tar -xzvf tiflash.tar.gz
        rm -f tiflash.tar.gz
        docker build -t ${DockerImage}-$ARCH .
        docker push ${DockerImage}-$ARCH
    """
}


pipeline {
    agent none
    stages {
        stage('Prepare') {
            steps {
                script {
                    TIFLASH_EDITION = "Community"
                    if (params.Edition == "enterprise") {
                        TIFLASH_EDITION = "Enterprise"
                    }
                }
            }
        }
        stage("multi-platform bin") {
        environment {
            NPROC = "16"
            TIFLASH_EDITION="$TIFLASH_EDITION"
        }
        parallel {
            stage("linux/amd64") {
                when {
                    expression {params.PathForLinuxAmd64}
                    beforeAgent true
                }
                agent { kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  restartPolicy: Never
  containers:
  - name: builder
    image: hub.pingcap.net/ee/ci/release-build-base-tiflash:v20231106
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
  nodeSelector:
    kubernetes.io/arch: amd64
  volumes:
  - name: ccache
    persistentVolumeClaim:
      claimName: ccache-dir-linux-amd64
  - name: cargohome
    persistentVolumeClaim:
      claimName: cargo-home-linux-amd64
  - name: rustuphome
    persistentVolumeClaim:
      claimName: rustup-home-linux-amd64
'''
                defaultContainer 'builder'
            } }
                environment {
                    OS = "linux"
                    ARCH = "amd64"
                    BinPath = "${params.PathForLinuxAmd64}"
                }
                steps {
                    script {
                        buildBin()
                    }
                }
            }
            stage("linux/arm64") {
                when {
                    expression {params.PathForLinuxArm64}
                    beforeAgent true
                }
                agent { kubernetes {
            yaml '''
apiVersion: v1
kind: Pod
spec:
  restartPolicy: Never
  containers:
  - name: builder
    image: hub.pingcap.net/ee/ci/release-build-base-tiflash:v20231106
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
        memory: "40Gi"
        cpu: "16"
      limits:
        memory: "40Gi"
        cpu: "16"
  nodeSelector:
    kubernetes.io/arch: arm64
  volumes:
  - name: ccache
    persistentVolumeClaim:
      claimName: ccache-dir-linux-arm64
  - name: cargohome
    persistentVolumeClaim:
      claimName: cargo-home-linux-arm64
  - name: rustuphome
    persistentVolumeClaim:
      claimName: rustup-home-linux-arm64
'''
                defaultContainer 'builder'
            } }
                environment {
                    OS = "linux"
                    ARCH = "arm64"
                    BinPath = "${params.PathForLinuxArm64}"
                }
                steps {
                    script {
                        buildBin()
                    }
                }
            }
            stage("darwin/amd64") {
                when {
                    beforeAgent true
                    allOf {
                        equals expected: "community", actual: params.Edition
                        expression {params.PathForDarwinAmd64}
                    }
                }
                agent { node { label 'darwin && amd64' } }
                environment {
                    OS = "darwin"
                    ARCH = "amd64"
                    BinPath = "${params.PathForDarwinAmd64}"
                    PATH = "/Users/pingcap/.cargo/bin:/bin:/sbin:/usr/bin:/usr/local/bin:/usr/local/go1.20/bin:/usr/local/opt/binutils/bin/:/usr/sbin"
                }
                steps {
                    script {
                        buildBin()
                    }
                }
            }
            stage("darwin/arm64") {
                when {
                    beforeAgent true
                    allOf {
                        equals expected: "community", actual: params.Edition
                        expression {params.PathForDarwinArm64}
                    }
                }
                agent { node { label 'darwin && arm64' } }
                environment {
                    OS = "darwin"
                    ARCH = "arm64"
                    BinPath = "${params.PathForDarwinArm64}"
                    PATH = "/Users/pingcap/.cargo/bin:/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/sbin:/usr/bin:/usr/local/bin:/usr/local/go1.20/bin:/usr/local/opt/binutils/bin/:/usr/sbin"
                }
                steps {
                    script {
                        buildBin()
                    }
                }
            }
        }
        }
        stage("multi-arch docker") {
            when {
                not {equals expected: "", actual: params.DockerImage}
                beforeAgent true
            }
            parallel {
                stage("amd64") {
                    when {
                        expression {params.PathForLinuxAmd64}
                        beforeAgent true
                    }
                    agent { node { label 'delivery' } }
                    environment {
                        ARCH = "amd64"
                        OS = "linux"
                        HUB = credentials('harbor-pingcap')
                        DOCKER_HOST = "tcp://localhost:2375"
                        BinPath = "${params.PathForLinuxAmd64}"
                    }
                    steps {container('delivery') {script {
                        buildDocker()
                    }}}
                }
                stage("arm64") {
                    when {
                        expression {params.PathForLinuxArm64}
                        beforeAgent true
                    }
                    agent { node { label 'arm_docker' } }
                    environment {
                        ARCH = "arm64"
                        OS = "linux"
                        HUB = credentials('harbor-pingcap')
                        BinPath = "${params.PathForLinuxArm64}"
                    }
                    steps {script {
                        buildDocker()
                    }}
                }
            }
        }
        stage("manifest docker image") {
            when {
                not {equals expected: "", actual: params.DockerImage}
                beforeAgent true
            }
            agent { node { label 'arm_docker' } }
            environment {
                HUB = credentials('harbor-pingcap')
            }
            steps {
                script {
                    def ammend = ""
                    if (params.PathForLinuxAmd64.toBoolean()) {
                        ammend += " -a ${DockerImage}-amd64"
                    }
                    if (params.PathForLinuxArm64.toBoolean()) {
                        ammend += " -a ${DockerImage}-arm64"
                    }
                    if (ammend == "") {
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
