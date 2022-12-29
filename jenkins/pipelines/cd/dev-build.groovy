final RepoDict = ["tidb":"tidb", "pd":"pd", "tiflash":"tics", "tikv":"tikv", "br":"tidb"]

def Repo = ''
def GitHash = ''
def GitPR = ''
def Image = ''
def ImageForGcr = ''
def BinPathDict = [:]
def PluginBinPathDict = [:]
def EnterprisePluginHash = ''

def ProductForBuild = ''
def NeedEnterprisePlugin = false

def get_dockerfile_url(arch){
    if (Version>='v6.6.0'){
        def fileName = Product
        if (Product == "tidb" && Edition == "enterprise") { 
            fileName = fileName + '-enterprise'
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/ci/rocky/jenkins/Dockerfile/release/rocky/${fileName}.Dockerfile"
    }else{
        def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${Product}"
        if (Product == "tidb" && Edition == "enterprise") { 
            dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/enterprise/${Product}"
        }
        return dockerfile
    }
}
pipeline{
    agent none
    parameters {
        choice(name: 'Product', choices : ["tidb", "tikv", "pd", "tiflash", "br"], description: 'the product to build, eg. tidb/tikv/pd')
        string(name: 'GitRef', description: 'the git tag or commit or branch or pull/id of repo')
        string(name: 'Version', description: 'important, the version for cli --version and profile choosing, eg. v6.5.0')
        choice(name: 'Edition', choices : ["community", "enterprise"])
        string(name: 'PluginGitRef', description: 'the git commit for enterprise plugin, only in enterprise tidb', defaultValue: "master")
    }
    stages{
        stage('prepare'){
            agent{
                kubernetes{
                    yaml '''
spec:
  containers:
  - name: gethash
    image: hub.pingcap.net/jenkins/gethash
'''
                    defaultContainer "gethash"
                }
            }
            steps{
                script{
                    Repo = RepoDict[Product]
                    GitHash = sh(returnStdout: true, script: "python /gethash.py -repo=$Repo -version=$GitRef").trim()
                    assert GitHash.length() == 40 : "invalid GitRef: $GitRef, returned hash is $GitHash"
                    if (GitRef ==~ 'pull/(\\d+)'){
                        GitPR = "${(GitRef =~ 'pull/(\\d+)')[0][1]}"
                    }
                    assert Version ==~ /v\d\.\d\.\d+.*/ : "invalid Version: $Version"
                    if (Product == "tidb" && Edition == "enterprise"){
                        NeedEnterprisePlugin = true
                    }
                    if (NeedEnterprisePlugin){
                        if (PluginGitRef == ""){
                            semverReg = /^(v)?(\d+\.\d+)(\.\d+.*)?/
                            PluginGitRef = String.format('release-%s', (Version =~ semverReg)[0][2])
                        }
                        echo "enterprise plugin commit: $PluginGitRef"
                        EnterprisePluginHash = sh(returnStdout: true, script: "python /gethash.py -repo=enterprise-plugin -version=${PluginGitRef}").trim()
                        echo "enterprise plugin hash: $EnterprisePluginHash"
                        PluginBinPathDict["amd64"] = "builds/devbuild/$BUILD_NUMBER/enterprise-plugin-linux-amd64.tar.gz"
                        PluginBinPathDict["arm64"] = "builds/devbuild/$BUILD_NUMBER/enterprise-plugin-linux-arm64.tar.gz"
                        echo "enterprise plugin bin path: $PluginBinPathDict"
                    }
                    Image = "hub.pingcap.net/devbuild/$Product:$Version-$BUILD_NUMBER"
                    BinPathDict["amd64"] = "builds/devbuild/$BUILD_NUMBER/$Product-linux-amd64.tar.gz"
                    BinPathDict["arm64"] = "builds/devbuild/$BUILD_NUMBER/$Product-linux-amd64.tar.gz"
                    ProductForBuild = Product
                    if (ProductForBuild == "tiflash"){
                        ProductForBuild = "tics"
                    }
                    def date = new Date()
                    def ts13 = date.getTime() / 1000
                    def ts10 = (Long) ts13
                    def day =new java.text.SimpleDateFormat("yyyyMMdd").format(date)
                    ImageForGcr = "gcr.io/pingcap-public/dbaas/$Product:$Version-$day-$ts10-dev"
                }
                echo "repo hash: $GitHash"
                echo "binary path: $BinPathDict"
                echo "image: $Image"
                echo "image on gcr: $ImageForGcr"
            }

        }
        stage('multi-arch build'){
            matrix{
                axes{
                    axis{
                        name "arch"
                        values "amd64", "arm64"
                    }
                }
                stages{
                    stage("bin"){
                        steps{
                            script{
                                def  paramsBuild = [
                                    string(name: "ARCH", value: arch),
                                    string(name: "OS", value: "linux"),
                                    string(name: "OS", value: "linux"),
                                    string(name: "EDITION", value: Edition),
                                    string(name: "OUTPUT_BINARY", value: BinPathDict[arch]),
                                    string(name: "REPO", value: Repo),
                                    string(name: "PRODUCT", value: ProductForBuild),
                                    string(name: "GIT_HASH", value: GitHash),
                                    string(name: "GIT_PR", value: GitPR),
                                    string(name: "RELEASE_TAG", value: Version),
                                    [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: false],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                ]
                                echo "$paramsBuild"
                                build job: "build-common",
                                    wait: true,
                                    parameters: paramsBuild
                            }
                        }
                    }
                    stage("enterprise plugin"){
                        when {expression{NeedEnterprisePlugin}}
                        steps{
                            script{
                                def paramsBuild = [
                                    string(name: "ARCH", value: arch),
                                    string(name: "OS", value: "linux"),
                                    string(name: "EDITION", value: Edition),
                                    string(name: "OUTPUT_BINARY", value: PluginBinPathDict[arch]),
                                    string(name: "REPO", value: "enterprise-plugin"),
                                    string(name: "PRODUCT", value: "enterprise-plugin"),
                                    string(name: "GIT_HASH", value: EnterprisePluginHash),
                                    string(name: "TIDB_HASH", value: GitHash),
                                    string(name: "GIT_PR", value: GitPR),
                                    string(name: "RELEASE_TAG", value: Version),
                                    [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: false],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                ]
                                echo "$paramsBuild"
                                build job: "debug-build-common",
                                    wait: true,
                                    parameters: paramsBuild
                            }
                        }
                    }
                    stage("docker"){
                        steps{
                            script{
                                def inputBin = BinPathDict[arch]
                                if (NeedEnterprisePlugin){
                                    inputBin = "$inputBin,${PluginBinPathDict[arch]}"
                                }
                                def paramsDocker = [
                                    string(name: "ARCH", value: arch),
                                    string(name: "OS", value: "linux"),
                                    string(name: "INPUT_BINARYS", value: inputBin),
                                    string(name: "REPO", value: ProductForBuild),
                                    string(name: "PRODUCT", value: ProductForBuild),
                                    string(name: "RELEASE_TAG", value: Version),
                                    string(name: "DOCKERFILE", value: get_dockerfile_url(arch)),
                                    string(name: "RELEASE_DOCKER_IMAGES", value: "$Image-$arch"),
                                ]
                                echo "$paramsDocker"
                                build job: "docker-common",
                                        wait: true,
                                        parameters: paramsDocker
                            }
                        }
                    }
                }
            }
        }
        stage("manifest multi-platform docker"){
            steps{
                script{
                def paramsManifest = [
                    string(name: "AMD64_IMAGE", value: "$Image-amd64"),
                    string(name: "ARM64_IMAGE", value: "$Image-arm64"),
                    string(name: "MULTI_ARCH_IMAGE", value: Image),
                ]
                echo "paramsManifest: ${paramsManifest}"
                build job: "manifest-multiarch-common",
                    wait: true,
                    parameters: paramsManifest
                }
            }
        }
        stage("push gcr"){
            steps{
                script{
                    def default_params = [
                        string(name: 'SOURCE_IMAGE', value: Image),
                        string(name: 'TARGET_IMAGE', value: ImageForGcr),
                    ]
                    echo "sync image ${Image} to ${ImageForGcr}"
                    build(job: "jenkins-image-syncer",
                            parameters: default_params,
                            wait: true)
                }
                
            }
        }
    }
}
