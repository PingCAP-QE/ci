final RepoDict = ["tidb":"tidb", "pd":"pd", "tiflash":"tics", "tikv":"tikv", "br":"tidb", "dumpling":"tidb", "tidb-lightning":"tidb", "ticdc":"tiflow", "dm":"tiflow", "tidb-binlog":"tidb-binlog"]
final FileserverDownloadURL = "http://fileserver.pingcap.net/download"

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
def NeedEnterprisePlugin = false
def PrintedVersion = ''


def get_dockerfile_url(arch){
    def fileName = Product
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
    parameters {
        choice(name: 'Product', choices : ["tidb", "tikv", "pd", "tiflash", "br", "dumpling", "tidb-lightning", "ticdc", "dm","tidb-binlog"], description: 'the product to build, eg. tidb/tikv/pd')
        string(name: 'GitRef', description: 'the git tag or commit or branch or pull/id of repo')
        string(name: 'Version', description: 'important, the version for cli --version and profile choosing, eg. v6.5.0')
        choice(name: 'Edition', choices : ["community", "enterprise"])
        string(name: 'PluginGitRef', description: 'the git commit for enterprise plugin, only in enterprise tidb', defaultValue: "master")
        string(name: 'GithubRepo', description: 'the github repo,just ignore unless in forked repo, eg pingcap/tidb', defaultValue: '')
        booleanParam(name: 'IsPushGCR', description: 'whether push gcr')
        booleanParam(name: 'IsHotfix', description: 'is it a hotfix build', defaultValue: false)
        string(name: 'Features', description: 'comma seperated features to build with', defaultValue: '')
        string(name: 'TiBuildID', description: 'the id of tibuild object, just leave empty if you do not know')
    }
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
            steps{
                script{
                    RepoForBuild = RepoDict[Product]
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
                    ProductForBuild = Product
                    if (ProductForBuild == "tiflash"){
                        ProductForBuild = "tics"
                    }
                    if (Product == "tidb-lightning"){
                        ProductForBuild = "br"
                    }
                    def date = new Date()
                    PipelineStartAt =new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(date)
                    def ts13 = date.getTime() / 1000
                    def ts10 = (Long) ts13
                    def day =new java.text.SimpleDateFormat("yyyyMMdd").format(date)
                    Image = "hub.pingcap.net/devbuild/$Product:$Version-$BUILD_NUMBER"
                    ImageForGcr = "gcr.io/pingcap-public/dbaas/$Product:$Version-$day-$ts10-dev"
                    if (params.IsHotfix.toBoolean()){
                        Image = "hub.pingcap.net/qa/$Product:$Version"
                        ImageForGcr = "gcr.io/pingcap-public/dbaas/$Product:$Version-$ts10"
                        BinPathDict["amd64"] = "builds/hotfix/$Product/$Version/$GitHash/centos7/$Product-linux-amd64.tar.gz"
                        BinPathDict["arm64"] = "builds/hotfix/$Product/$Version/$GitHash/centos7/$Product-linux-arm64.tar.gz"
                        PluginBinPathDict["amd64"] = "builds/hotfix/enterprise-plugin/$Version/$EnterprisePluginHash/centos7/enterprise-plugin-linux-amd64.tar.gz"
                        PluginBinPathDict["arm64"] = "builds/hotfix/enterprise-plugin/$Version/$EnterprisePluginHash/centos7/enterprise-plugin-linux-arm64.tar.gz"
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
                                ["platform":"linux/amd64", "url": "$FileserverDownloadURL/${BinPathDict['amd64']}", "sha256URL":"$FileserverDownloadURL/${BinPathDict['amd64']}.sha256"],
                                ["platform":"linux/arm64", "url": "$FileserverDownloadURL/${BinPathDict['arm64']}", "sha256URL":"$FileserverDownloadURL/${BinPathDict['arm64']}.sha256"],
                            ],
                            "printedVersion": PrintedVersion
                        ]]]
                    if (NeedEnterprisePlugin){
                        def plugin_bins =[
                                ["platform":"linux/amd64", "url": "$FileserverDownloadURL/${PluginBinPathDict['amd64']}", "sha256URL":"$FileserverDownloadURL/${PluginBinPathDict['amd64']}.sha256"],
                                ["platform":"linux/arm64", "url": "$FileserverDownloadURL/${PluginBinPathDict['arm64']}", "sha256URL":"$FileserverDownloadURL/${PluginBinPathDict['arm64']}.sha256"],
                            ]
                        dev_build["status"]["buildReport"]["binaries"].addAll(plugin_bins)
                        dev_build["status"]["buildReport"]["pluginGitHash"] = EnterprisePluginHash
                    }
                    if (params.IsPushGCR.toBoolean()){
                        dev_build["status"]["buildReport"]["images"].add(["platform":"multi-arch", "url":ImageForGcr])
                    }
                    node("mac"){
                        writeJSON file:"status.json", json: dev_build
                        sh "curl -X PUT 'https://tibuild.pingcap.net/api/devbuilds/$TiBuildID' -d @status.json"
                    }
                }
            }
        }
        unsuccessful{
            script{
                if (TiBuildID!=""){
                    node("mac"){sh "curl 'https://tibuild.pingcap.net/api/devbuilds/$TiBuildID?sync=true'"}
                }
            }
        }
    }
}
