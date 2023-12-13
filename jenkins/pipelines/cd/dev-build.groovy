final RepoDict = ["tiflash":"tics", "br":"tidb", "dumpling":"tidb", "tidb-lightning":"tidb", 
    "ticdc":"tiflow", "dm":"tiflow", "drainer":"tidb-binlog", "pump":"tidb-binlog"]
final ProductForBuildMapping = ["tiflash":"tics", "tidb-lightning":"br", "drainer":"tidb-binlog", "pump":"tidb-binlog"]
final DockerMapping = ["drainer":"tidb-binlog", "pump":"tidb-binlog"]
final FileserverDownloadURL = "https://fileserver.pingcap.net/download"

def GitHash = ''
def GitPR = ''
def Image = ''
def ImageForGcr = ''
def BinPathDict = [:]
def BinBuildPathDict = [:]
def PluginBinPathDict = [:]
def EnterprisePluginHash = ''
def PipelineStartAt = ''
def PipelineEndAt = ''

def RepoForBuild = ''
def ProductForBuild = ''
def ProductForDocker = ''
def NeedEnterprisePlugin = false
def PrintedVersion = ''


def get_dockerfile_url={arch ->
    def fileName = ProductForDocker
    if (params.ProductDockerfile){
        return params.ProductDockerfile
    }
    if (params.ProductBaseImg){
        if (Product == "tidb" && Edition == "enterprise") {
            fileName = fileName + '-enterprise'
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/${fileName}.Dockerfile"
    }
    if (Version>='v6.6.0'){
        if (Product == "tidb" && Edition == "enterprise") { 
            fileName = fileName + '-enterprise'
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/${fileName}.Dockerfile"
    }else{
        if (Product == "tidb" && Edition == "enterprise") { 
            fileName = "enterprise/${Product}"
        }
        return "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-${arch}/${fileName}"
    }
}
pipeline{
    agent none
    stages{
        stage('prepare'){
            agent{
                kubernetes{
                    yaml '''
spec:
  containers:
  - name: gethash
    image: hub.pingcap.net/jenkins/gethash:latest
    imagePullPolicy: Always
    command: ["sleep", "infinity"]
'''
                    defaultContainer "gethash"
                }
            }
            environment {GHTOKEN = credentials('github-token-gethash')}
            steps{
                script{
                    RepoForBuild = RepoDict.getOrDefault(Product, Product)
                    def hash_repo = params.GithubRepo ? params.GithubRepo : RepoForBuild
                    GitHash = sh(returnStdout: true, script: "python /gethash.py -repo=$hash_repo -version=$GitRef").trim()
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
                    BinPathDict["amd64"] = "builds/devbuild/$BUILD_NUMBER/$Product-linux-amd64.tar.gz"
                    BinPathDict["arm64"] = "builds/devbuild/$BUILD_NUMBER/$Product-linux-arm64.tar.gz"
                    BinBuildPathDict["amd64"] = "builds/devbuild/$BUILD_NUMBER/$Product-build-linux-amd64.tar.gz"
                    BinBuildPathDict["arm64"] = "builds/devbuild/$BUILD_NUMBER/$Product-build-linux-arm64.tar.gz"
                    ProductForBuild = ProductForBuildMapping.getOrDefault(Product, Product)
                    ProductForDocker = DockerMapping.getOrDefault(Product, Product)
                    def date = new Date()
                    PipelineStartAt =new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(date)
                    Image = "hub.pingcap.net/devbuild/$ProductForDocker:$Version-$BUILD_NUMBER"
                    if (params.TargetImg!=""){
                        Image = params.TargetImg
                    }
                    ImageForGcr = "gcr.io/pingcap-public/dbaas/$ProductForDocker:$Version-$BUILD_NUMBER-dev"
                    if (params.IsHotfix.toBoolean()){
                        Image = "hub.pingcap.net/qa/$ProductForDocker:$Version-$BUILD_NUMBER"
                        if (params.Features != ""){
                            error "hotfix artifact but with extra features"
                        }
                        ImageForGcr = "gcr.io/pingcap-public/dbaas/$ProductForDocker:$Version"
                        BinPathDict["amd64"] = "builds/hotfix/$Product/$Version/$BUILD_NUMBER/$Product-patch-linux-amd64.tar.gz"
                        BinPathDict["arm64"] = "builds/hotfix/$Product/$Version/$BUILD_NUMBER/$Product-patch-linux-arm64.tar.gz"
                        PluginBinPathDict["amd64"] = "builds/hotfix/enterprise-plugin/$Version/$BUILD_NUMBER/enterprise-plugin-linux-amd64.tar.gz"
                        PluginBinPathDict["arm64"] = "builds/hotfix/enterprise-plugin/$Version/$BUILD_NUMBER/enterprise-plugin-linux-arm64.tar.gz"
                    }
                }
                echo "repo hash: $GitHash"
                echo "binary amd64 path: $FileserverDownloadURL/${BinPathDict['amd64']}"
                echo "binary arm64 path: $FileserverDownloadURL/${BinPathDict['arm64']}"
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
                                    string(name: "OUTPUT_BINARY", value: BinBuildPathDict[arch]),
                                    string(name: "REPO", value: RepoForBuild),
                                    string(name: "PRODUCT", value: ProductForBuild),
                                    string(name: "GIT_HASH", value: GitHash),
                                    string(name: "GIT_PR", value: GitPR),
                                    string(name: "RELEASE_TAG", value: Version),
                                    string(name: "GITHUB_REPO", value: params.GithubRepo),
                                    string(name: "BUILD_ENV", value: params.BuildEnv),
                                    string(name: "BUILDER_IMG", value: params.BuilderImg),
                                    [$class: 'BooleanParameterValue', name: 'NEED_SOURCE_CODE', value: false],
                                    [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: true],
                                    [$class: 'BooleanParameterValue', name: 'FAILPOINT', value: params.Features.contains('failpoint')],
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
                                def tidb_hash = params.GithubRepo ? "${params.GithubRepo}:$GitHash" : GitHash
                                def paramsBuild = [
                                    string(name: "ARCH", value: arch),
                                    string(name: "OS", value: "linux"),
                                    string(name: "EDITION", value: Edition),
                                    string(name: "OUTPUT_BINARY", value: PluginBinPathDict[arch]),
                                    string(name: "REPO", value: "enterprise-plugin"),
                                    string(name: "PRODUCT", value: "enterprise-plugin"),
                                    string(name: "GIT_HASH", value: EnterprisePluginHash),
                                    string(name: "TIDB_HASH", value: tidb_hash),
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
                    stage("package tiup"){
                        agent{
                            kubernetes{
                                yaml '''
spec:
  containers:
  - name: package-tiup
    image: hub.pingcap.net/jenkins/package-tiup:latest
    imagePullPolicy: Always
    command: ["sleep", "infinity"]
'''
                                defaultContainer "package-tiup"
                            }
                        }
                        steps{
                           sh "package_tiup.py $Product ${BinPathDict[arch]} ${BinBuildPathDict[arch]}" 
                        }
                    }
                    stage("docker"){
                        steps{
                            script{
                                def inputBin = BinBuildPathDict[arch]
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
                                    string(name: "BASE_IMG", value: params.ProductBaseImg),
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
        stage("print version"){
            when {
                beforeAgent true
                equals expected:true, actual:params.IsHotfix.toBoolean()
            }
            agent{
                kubernetes{
                    yaml '''
spec:
  containers:
  - name: print-version
    image: hub.pingcap.net/jenkins/print-version:latest
    imagePullPolicy: Always
    command: ["sleep", "infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://localhost:2375
  - name: dind
    image: docker:dind
    args: ["--registry-mirror=https://registry-mirror.pingcap.net"]
    env:
    - name: DOCKER_TLS_CERTDIR
      value: ""
    - name: DOCKER_HOST
      value: tcp://localhost:2375
    securityContext:
      privileged: true
    readinessProbe:
      exec:
        command: ["docker", "info"]
      initialDelaySeconds: 10
      failureThreshold: 6
'''
                    defaultContainer "print-version"
                }
            }
            steps{
                script{
                    PrintedVersion = sh(returnStdout: true, script: "print_version.py $Product $Image || true").trim()
                }
                echo PrintedVersion
            }
        }
        stage("push gcr"){
            when {equals expected:true, actual:params.IsPushGCR.toBoolean()}
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
    post {
        success {
            script{
                def date = new Date()
                PipelineEndAt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(date)
                if (TiBuildID!=""){
                    def dev_build = ["status":["status":"SUCCESS", "pipelineBuildID":BUILD_NUMBER.toInteger(),
                        "pipelineStartAt":PipelineStartAt , "pipelineEndAt":PipelineEndAt , "buildReport":[
                            "gitHash":GitHash,
                            "images":[["platform":"multi-arch", "url":Image]],
                            "binaries":[
                                ["component":"${params.Product}", "platform":"linux/amd64", "url": "$FileserverDownloadURL/${BinPathDict['amd64']}", "sha256URL":"$FileserverDownloadURL/${BinPathDict['amd64']}.sha256"],
                                ["component":"${params.Product}", "platform":"linux/arm64", "url": "$FileserverDownloadURL/${BinPathDict['arm64']}", "sha256URL":"$FileserverDownloadURL/${BinPathDict['arm64']}.sha256"],
                            ],
                            "printedVersion": PrintedVersion
                        ]]]
                    if (NeedEnterprisePlugin){
                        def plugin_bins =[
                                ["component":"enterprise-plugin", "platform":"linux/amd64", "url": "$FileserverDownloadURL/${PluginBinPathDict['amd64']}", "sha256URL":"$FileserverDownloadURL/${PluginBinPathDict['amd64']}.sha256"],
                                ["component":"enterprise-plugin", "platform":"linux/arm64", "url": "$FileserverDownloadURL/${PluginBinPathDict['arm64']}", "sha256URL":"$FileserverDownloadURL/${PluginBinPathDict['arm64']}.sha256"],
                            ]
                        dev_build["status"]["buildReport"]["binaries"].addAll(plugin_bins)
                        dev_build["status"]["buildReport"]["pluginGitHash"] = EnterprisePluginHash
                    }
                    if (params.IsPushGCR.toBoolean()){
                        dev_build["status"]["buildReport"]["images"].add(["platform":"multi-arch", "url":ImageForGcr])
                    }
                    node("light_curl"){
                        writeJSON file:"status.json", json: dev_build
                        sh "curl -X PUT 'https://tibuild.pingcap.net/api/devbuilds/$TiBuildID' -d @status.json"
                    }
                }
            }
        }
        unsuccessful{
            script{
                if (TiBuildID!=""){
                    node("light_curl"){sh "curl 'https://tibuild.pingcap.net/api/devbuilds/$TiBuildID?sync=true'"}
                }
            }
        }
    }
}
