final RepoDict = [
    "tiflash":"tics",
    "br":"tidb",
    "dumpling":"tidb",
    "tidb-lightning":"tidb",
    "ticdc":"tiflow",
    "ticdc-newarch": "ticdc",
    "dm":"tiflow",
    "drainer":"tidb-binlog",
    "pump":"tidb-binlog",
]
final ProductForBuildMapping = [
    "tiflash":"tics",
    "tidb-lightning":"br",
    "drainer":"tidb-binlog",
    "pump":"tidb-binlog",
]

// image prefix with `hub.pingcap.net/devbuild/` for no hotfix build and `hub.pingcap.net/` for hotfix build.
final DockerImgRepoMapping = [
    "tidb-binlog": "pingcap/tidb-binlog/image",
    "drainer":"pingcap/tidb-binlog/image",
    "pump":"pingcap/tidb-binlog/image",
    "tidb": "pingcap/tidb/images/tidb-server",
    "tidb-lightning": "pingcap/tidb/images/tidb-lightning",
    "br": "pingcap/tidb/images/br",
    "dumpling": "pingcap/tidb/images/dumpling",
    "tidb-tools": "pingcap/tidb-tools/image",
    "tidb-dashboard": "pingcap/tidb-dashboard/image",
    "tikv": "tikv/tikv/image",
    "pd": "tikv/pd/image",
    "tiflash": "pingcap/tiflash/image",
    "dm": "pingcap/tiflow/images/dm",
    "ticdc": "pingcap/tiflow/images/cdc",
    "ticdc-newarch": "pingcap/ticdc/image",
    "ng-monitoring": "pingcap/ng-monitoring/image",
    "tidb-dashboard": "pingcap/tidb-dashboard/image",
]

// image prefix with `gcr.io/pingcap-public/dbaas/`
final GcrDockerImgRepoMapping = ["drainer":"tidb-binlog", "pump":"tidb-binlog", "ticdc-newarch":"ticdc"]

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
def ShortImageRepoForGCR = ''
def NeedEnterprisePlugin = false
def PrintedVersion = ''

def semverCompare = { v1, v2 ->
    // Remove leading 'v' if present
    def normalize = { v ->
        v = v.toString().replaceFirst(/^v/, '')
        // Split by '-' to separate pre-release/build metadata
        def parts = v.split('-', 2)
        def nums = parts[0].split('\\.')
        def major = nums.length > 0 ? nums[0].toInteger() : 0
        def minor = nums.length > 1 ? nums[1].toInteger() : 0
        def patch = nums.length > 2 ? nums[2].replaceAll(/[^0-9].*$/, '').toInteger() : 0
        def pre = parts.length > 1 ? parts[1] : ''
        return [major, minor, patch, pre]
    }
    def a = normalize(v1)
    def b = normalize(v2)
    for (int i = 0; i < 3; i++) {
        if (a[i] < b[i]) return -1
        if (a[i] > b[i]) return 1
    }
    // Handle pre-release: empty string means stable, which is greater than any pre-release
    if (a[3] == b[3]) return 0
    if (a[3] == '') return 1
    if (b[3] == '') return -1
    // Lexical compare pre-release
    return a[3] <=> b[3]
}

def get_dockerfile_url={arch ->
    if (params.ProductDockerfile){
        return params.ProductDockerfile
    }
    // Use semverCompare to check if Version >= v6.6.0
    if (semverCompare(Version, 'v6.6.0') >= 0){
        if (Product == "tidb" && Edition == "enterprise") {
            return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/tidb.enterprise.Dockerfile"
        }
        // note new ticdc only supports from v9.0.0
        return [
            "tidb":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/tidb.Dockerfile",
            "tidb-lightning":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/tidb-lightning.Dockerfile",
            "br":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/br.Dockerfile",
            "dumpling":         "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/dumpling.Dockerfile",
            "tiflash":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflash/Dockerfile",
            "dm":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/dm.Dockerfile",
            "ticdc":            "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/ticdc.Dockerfile",
            "ticdc-newarch":    "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/ticdc/Dockerfile",
            "drainer":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/Dockerfile",
            "pump":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/Dockerfile",
            "tidb-tools":       "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-tools/Dockerfile",
            "tidb-dashboard":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-dashboard/Dockerfile",
            "tikv":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tikv/Dockerfile",
            "pd":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/pd/Dockerfile",
            "ng-monitoring":    "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/ng-monitoring/Dockerfile",
        ][Product]
    } else if (semverCompare(Version, 'v6.5.12') >= 0) {
        if (Product == "tidb" && Edition == "enterprise") {
            return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/~6.5.12/tidb.enterprise.Dockerfile"
        }

        return [
            "tidb":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/~6.5.12/tidb.Dockerfile",
            "tidb-lightning":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/~6.5.12/tidb-lightning.Dockerfile",
            "br":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/~6.5.12/br.Dockerfile",
            "dumpling":         "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/~6.5.12/dumpling.Dockerfile",
            "tiflash":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflash/~6.5.12/Dockerfile",
            "dm":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/~6.5.12/dm.Dockerfile",
            "ticdc":            "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/~6.5.12/ticdc.Dockerfile",
            "drainer":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/~6.5.12/Dockerfile",
            "pump":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/~6.5.12/Dockerfile",
            "tidb-tools":       "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-tools/~6.5.12/Dockerfile",
            "tidb-dashboard":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-dashboard/~6.5.12/Dockerfile",
            "tikv":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tikv/~6.5.12/Dockerfile",
            "pd":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/pd/~6.5.12/Dockerfile",
            "ng-monitoring":    "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/ng-monitoring/~6.5.12/Dockerfile",
        ][Product]
    } else {
        if (Product == "tidb" && Edition == "enterprise") {
            return "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/lt6.5.12/tidb.enterprise.Dockerfile"
        }

        return [
            "tidb":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/lt6.5.12/tidb.Dockerfile",
            "tidb-lightning":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/lt6.5.12/tidb-lightning.Dockerfile",
            "br":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/lt6.5.12/br.Dockerfile",
            "dumpling":         "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb/lt6.5.12/dumpling.Dockerfile",
            "tiflash":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflash/lt6.5.12/Dockerfile",
            "dm":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/lt6.5.12/dm.Dockerfile",
            "ticdc":            "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tiflow/lt6.5.12/ticdc.Dockerfile",
            "drainer":          "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/lt6.5.12/Dockerfile",
            "pump":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-binlog/lt6.5.12/Dockerfile",
            "tidb-tools":       "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-tools/lt6.5.12/Dockerfile",
            "tidb-dashboard":   "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tidb-dashboard/lt6.5.12/Dockerfile",
            "tikv":             "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/tikv/lt6.5.12/Dockerfile",
            "pd":               "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/pd/lt6.5.12/Dockerfile",
            "ng-monitoring":    "https://raw.githubusercontent.com/PingCAP-QE/artifacts/main/dockerfiles/products/ng-monitoring/lt6.5.12/Dockerfile",
        ][Product]
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
    image: hub.pingcap.net/jenkins/gethash:v20250526
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
                    ProductForDocker = DockerImgRepoMapping.getOrDefault(Product, Product)
                    ShortImageRepoForGCR = GcrDockerImgRepoMapping.getOrDefault(Product, Product)
                    def date = new Date()
                    PipelineStartAt =new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(date)
                    Image = "hub.pingcap.net/devbuild/$ProductForDocker:$Version-$BUILD_NUMBER"
                    if (params.TargetImg!=""){
                        Image = params.TargetImg
                    }
                    ImageForGcr = "gcr.io/pingcap-public/dbaas/$ShortImageRepoForGCR:$Version-$BUILD_NUMBER-dev"

                    if (params.IsHotfix.toBoolean()){
                        Image = "hub.pingcap.net/$ProductForDocker:$Version-$BUILD_NUMBER"
                        if (params.Features != ""){
                            error "hotfix artifact but with extra features"
                        }
                        ImageForGcr = "gcr.io/pingcap-public/dbaas/$ShortImageRepoForGCR:$Version"
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
        stage('build by sub pipeline'){
            when{expression{params.Product == "tidb-dashboard"}}
            steps{
                script{
                    def simpleGitRef = params.GitRef.replaceFirst('(^branch/)|(^tag/)','')
                    def  paramsBuild = [
                                    string(name: "GitRef", value: simpleGitRef),
                                    string(name: "ReleaseTag", value: params.Version),
                                    [$class: 'BooleanParameterValue', name: 'IsDevbuild', value: true],
                                    string(name: "BinaryPrefix", value: "builds/devbuild/$BUILD_NUMBER"),
                                    string(name: "DockerImg", value: Image),
                                    string(name: "BuildEnv", value: params.BuildEnv),
                    ]
                    build job: "build-tidb-dashboard",
                                    wait: true,
                                    parameters: paramsBuild
                }
            }
        }

        stage('multi-arch build'){
            when{expression{params.Product != "tidb-dashboard"}}
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
                                    string(name: "USE_TIFLASH_RUST_CACHE", value: 'true'),
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
                            sh label: 'package-tiup', script: """
                                MAX_RETRIES=3
                                RETRY_COUNT=0

                                while [ \$RETRY_COUNT -lt \$MAX_RETRIES ]; do
                                    RETRY_COUNT=\$((RETRY_COUNT + 1))
                                    echo "Attempting package_tiup.py (attempt \$RETRY_COUNT/\$MAX_RETRIES)..."

                                    if package_tiup.py ${Product} ${BinPathDict[arch]} ${BinBuildPathDict[arch]}; then
                                        echo "package_tiup.py executed successfully"
                                        exit 0
                                    else
                                        if [ \$RETRY_COUNT -lt \$MAX_RETRIES ]; then
                                            echo "package_tiup.py attempt \$RETRY_COUNT failed, retrying in 5 seconds..."
                                            sleep 5
                                        else
                                            echo "package_tiup.py failed after \$MAX_RETRIES attempts"
                                            exit 1
                                        fi
                                    fi
                                done
                            """
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
            when{expression{params.Product != "tidb-dashboard"}}
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
