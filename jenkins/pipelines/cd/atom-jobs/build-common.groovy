/*
* @OUTPUT_BINARY(string:binary url on fileserver, transfer througth atom jobs,Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @GIT_HASH(string:to get correct code from github,Required)
* @GIT_PR(string:generate ref head to pre get code from pr,Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @TARGET_BRANCH(string:for daily CI workflow,Optional)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
* @FAILPOINT(bool:build failpoint binary or not,only for tidb,tikv,pd now ,default false,Optional)
* @EDITION(enumerate:,community,enterprise,Required)
* @USE_TIFLASH_RUST_CACHE(string:use rust code cache, for tiflash only, Optional)
*/


taskStartTimeInMillis = System.currentTimeMillis()
taskFinishTimeInMillis = System.currentTimeMillis()
checkoutStartTimeInMillis = System.currentTimeMillis()
checkoutFinishTimeInMillis = System.currentTimeMillis()
compileStartTimeInMillis = System.currentTimeMillis()
compileFinishTimeInMillis = System.currentTimeMillis()
uploadStartTimeInMillis = System.currentTimeMillis()
uploadFinishTimeInMillis = System.currentTimeMillis()

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

failpoint = "false"
if (params.FAILPOINT) {
    failpoint = "true"
}


// check if binary already has been built.
def ifFileCacheExists() {
    // return false // to re-run force build
    node("light_curl") {
            if (params.FORCE_REBUILD){
                return false
            }
            result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
            // result equal 0 mean cache file exists
            if (result == 0) {
                echo "file ${FILE_SERVER_URL}/download/${OUTPUT_BINARY} found in cache server,skip build again"
                return true
            }
            return false
    }
}

// support branch example
//  master | hz-poc
//  relase-4.0
//  release-4.0-20210812
//  release-5.1
//  release-5.3

// choose which go version to use.
def String needUpgradeGoVersion(String tag,String branch) {
    goVersion="go1.23"
    // tidb-tools only use branch master and use newest go version
    // only for version >= v5.3.0
    if (REPO=="tidb-tools" && tag>="v5.3"){
        return goVersion
    }
    if (tag.startsWith("v") && tag >= "v7.4" && tag < "v8.4") {
        return "go1.21"
    }
    if (tag.startsWith("v") && tag >= "v7.0" && tag < "v7.4") {
        return "go1.20"
    }
    if (tag.startsWith("v") && tag >= "v6.3" && tag < "v6.7") {
        return "go1.19"
    }
    if (tag.startsWith("v") && tag <= "v5.1") {
        return "go1.13"
    }
    if (tag.startsWith("v") && tag > "v5.1" && tag < "v6.0") {
        return "go1.16"
    }
    // special for v6.1 larger than patch 3
    if (tag.startsWith("v6.1") && tag >= "v6.1.3" || tag=="v6.1.0-nightly") {
        return "go1.19"
    }
    if (tag.startsWith("v") && tag >= "v6.0" && tag < "v6.3") {
        return "go1.18"
    }
    if (branch.startsWith("release-") && branch < "release-5.1"){
        return "go1.13"
    }
    if (branch.startsWith("release-") && branch >= "release-5.1" && branch < "release-6.0"){
        return "go1.16"
    }
    // special for release-6.1 later versions
    if (branch == "release-6.1"){
        return "go1.19"
    }
    if (branch.startsWith("release-") && branch >= "release-6.0" && branch < "release-6.3"){
        return "go1.18"
    }
    if (branch.startsWith("release-") && branch >= "release-6.3" && branch < "release-6.7"){
        return "go1.19"
    }
    if (branch.startsWith("release-") && branch >= "release-7.0" && branch < "release-7.4"){
        return "go1.20"
    }
    if (branch.startsWith("release-") && branch >= "release-7.4" && branch < "release-8.4"){
        return "go1.21"
    }
    if (branch.startsWith("hz-poc") || branch.startsWith("arm-dup") ) {
        return "go1.16"
    }
    if (REPO == "tiem") {
        return "go1.16"
    }
    return "go1.23"
}

def goBuildPod = "build_go1230"
def GO_BIN_PATH = "/usr/local/go1.23/bin"
goVersion = needUpgradeGoVersion(params.RELEASE_TAG,params.TARGET_BRANCH)
switch (goVersion) {
    case "go1.23":
        goBuildPod = "build_go1230"
        GO_BIN_PATH = "/usr/local/go1.23/bin"
        break
    case "go1.21":
        goBuildPod = "build_go1210"
        GO_BIN_PATH = "/usr/local/go1.21/bin"
        break
    case "go1.20":
        goBuildPod = "build_go1200"
        GO_BIN_PATH = "/usr/local/go1.20/bin"
        break
    case "go1.19":
        goBuildPod = "build_go1190"
        GO_BIN_PATH = "/usr/local/go1.19/bin"
        break
    case "go1.18":
        goBuildPod = "build_go1180"
        GO_BIN_PATH = "/usr/local/go1.18.10/bin"
        break
    case "go1.16":
        goBuildPod = "${GO1160_BUILD_SLAVE}"
        GO_BIN_PATH = "/usr/local/go1.16.4/bin"
        break
    case "go1.13":
        goBuildPod = "${GO_BUILD_SLAVE}"
        GO_BIN_PATH = "/usr/local/go/bin"
        break
    default:
        throw new Exception("go version ${goVersion} not supported")
}

// choose which node to use.
def nodeLabel = goBuildPod
def containerLabel = "golang"
def binPath = ""
def useArmPodTemplate = false

if (params.ARCH == "arm64" && params.OS == "linux" && !(params.PRODUCT in ["tics", "tiflash"])) {
    useArmPodTemplate = true
}
if (params.PRODUCT == "tikv" || params.PRODUCT == "importer") {
    nodeLabel = "build"
    containerLabel = "rust"
}
if (params.PRODUCT == "tics") {
    nodeLabel = "build_tiflash"
    containerLabel = "tiflash-llvm"
    if (params.ARCH == "arm64" && params.OS == "linux"){
        nodeLabel = "tiflash_build_arm"
        containerLabel = "tiflash-llvm"
    }
}
if (params.ARCH == "arm64" && params.OS == "linux" && !useArmPodTemplate && params.PRODUCT != "tics") {
    binPath = "${GO_BIN_PATH}:/usr/local/node/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin"
    nodeLabel = "arm"
    containerLabel = ""
    error "should not use physical node"
}
if (params.OS == "darwin" && params.ARCH == "amd64") {
    binPath = "${GO_BIN_PATH}:/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/opt/binutils/bin/"
    nodeLabel = "mac"
    containerLabel = ""
}
if (params.OS == "darwin" && params.ARCH == "arm64") {
    binPath = "${GO_BIN_PATH}:/opt/homebrew/bin:/opt/homebrew/sbin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/opt/binutils/bin/"
    nodeLabel = "mac-arm"
    containerLabel = ""
    if (params.PRODUCT == "tics"){
        nodeLabel = "mac-arm-tiflash"
    }
}

if (binPath){
    env.PATH = binPath
}

// define git url and git ref.
repo = "git@github.com:pingcap/${REPO}.git"
if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
    repo = "git@github.com:tikv/${REPO}.git"
}
if (REPO == "tiem" || REPO == "enterprise-plugin") {
    repo = "git@github.com:pingcap-inc/${REPO}.git"
}
if (GITHUB_REPO){
    repo = "git@github.com:${GITHUB_REPO}.git"
}
specRef = "+refs/heads/*:refs/remotes/origin/*"
if (params.GIT_PR.length() >= 1) {
   specRef = "+refs/pull/${GIT_PR}/*:refs/remotes/origin/pr/${GIT_PR}/*"
}
def checkoutCode() {
    try{
        def codeCacheInFileserverUrl = "cicd/daily-cache-code/src-${REPO}.tar.gz"
        println "get code from fileserver to reduce clone time"
        println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
        download_fileserver(codeCacheInFileserverUrl, "src-${REPO}.tar.gz")
        sh """
        tar -xzf src-${REPO}.tar.gz --strip-components=1
        rm -f src-${REPO}.tar.gz
        rm -rf ./*
        """
    }catch(err){
        echo "Caught: ${err}"
        println "get code from github"
    }
    retry(3) {
        checkout changelog: false, poll: true,
                        scm: [$class: 'GitSCM', branches: [[name: "${GIT_HASH}"]], doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'CheckoutOption', timeout: 30],
                                        [$class: 'CloneOption', timeout: 60],
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                        [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                refspec      : specRef,
                                                url          : repo]]]
    }
    sh "git config --global --add safe.directory '*'"
    sh 'test -z "$(git status --porcelain)"'
    if(params.PRODUCT == 'enterprise-plugin'){
        sh """
        cd ../
        curl -O -C - --retry 5 --retry-delay 6 --retry-max-time 60 ${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb.tar.gz
        tar -xf src-tidb.tar.gz
        rm -f src-tidb.tar.gz
        """
        def tidb_repo = 'git@github.com:pingcap/tidb.git'
        if (TIDB_HASH.contains(':')){
            def tidb_repo_hash = TIDB_HASH.split(":")
            tidb_repo = "git@github.com:${tidb_repo_hash[0]}.git"
            TIDB_HASH = tidb_repo_hash[1]
        }
        dir('../tidb'){
            retry(3) {
                checkout changelog: false, poll: true,
                        scm: [$class: 'GitSCM', branches: [[name: "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'CheckoutOption', timeout: 30],
                                        [$class: 'CloneOption', timeout: 60],
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'SubmoduleOption', timeout: 30, disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''],
                                        [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                refspec      : specRef,
                                                url          : tidb_repo]]]
            }
            sh 'test -z "$(git status --porcelain)"'
        }
    }
}


// define build script here.
TARGET = "output"
buildsh = [:]
buildsh["tidb-ctl"] = """
go version
go build -o binarys/${PRODUCT}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/${PRODUCT} ${TARGET}/bin/
"""

def BuildCmd = """
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
if [ ${OS} == 'linux' ]; then
    WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
    git checkout .
    WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
    git checkout .
    make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
    git checkout .
    make server_coverage || true
    git checkout .
    if [ \$(grep -E '^ddltest:' Makefile) ]; then
        git checkout .
        make ddltest
    fi

    if [ \$(grep -E '^importer:' Makefile) ]; then
        git checkout .
        make importer
    fi
fi;
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
make
"""
if (params.PRODUCT == 'tidb' && params.EDITION == 'enterprise' && params.RELEASE_TAG >= "v7.1.0") {
    BuildCmd = "make enterprise-prepare enterprise-server-build"
}

buildsh["tidb"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ "${EDITION}" = 'enterprise' ]; then
    export TIDB_EDITION=Enterprise
fi;
go version
make clean
git checkout .
${BuildCmd}
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp binarys/tidb-ctl ${TARGET}/bin/ || true
cp bin/* ${TARGET}/bin/
"""

buildsh["tidb-binlog"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make clean
git checkout .
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["pd"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
git checkout .
if [ ${EDITION} == 'enterprise' ]; then
    export PD_EDITION=Enterprise
fi;
if [ ${failpoint} == 'true' ]; then
    export WITH_RACE=1
    export FAILPOINT=1
    make failpoint-enable
fi;
make
make tools
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["tidb-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make clean
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["ticdc"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make build
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["ticdc-newarch"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make cdc
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

// only support dm version >= 5.3.0 (dm in repo tiflow)
// start from 6.0.0, dm use webui is supported
dmUseWebUI = "true"
if ((params.RELEASE_TAG.startsWith("release-") && params.RELEASE_TAG <"release-6.0") || (params.RELEASE_TAG.startsWith("v") && params.RELEASE_TAG <"v6.0.0")) {
    dmUseWebUI = "false"
}
dmNodePackage = "node-v16.14.0-linux-x64"
if (params.OS == "linux" && params.ARCH == "arm64") {
    dmNodePackage = "node-v16.14.0-linux-arm64"
} else if (params.OS == "darwin" && params.ARCH == "arm64") {
    dmNodePackage = "node-v16.14.0-darwin-arm64"
} else if (params.OS == "darwin" && params.ARCH == "amd64") {
    dmNodePackage = "node-v16.14.0-darwin-x64"
} else {
    dmNodePackage = "node-v16.14.0-linux-x64"
}

buildsh["dm"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;

go version
if [ ${dmUseWebUI} == "true" ]; then
    wget http://fileserver.pingcap.net/download/ee-tools/${dmNodePackage}.tar.gz
    tar -xvf ${dmNodePackage}.tar.gz
    export PATH=\$(pwd)/${dmNodePackage}/bin:\$PATH
    node -v
    npm install -g yarn
    make dm-master-with-webui dm-worker dmctl dm-syncer
else
    make dm
fi;

ls -alh bin/
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
mkdir -p ${TARGET}/conf

if [[ -d "dm/dm" ]]; then
    mv dm/dm/master/task_basic.yaml ${TARGET}/conf/
    mv dm/dm/master/task_advanced.yaml ${TARGET}/conf/
    mv dm/dm/master/dm-master.toml ${TARGET}/conf/
    mv dm/dm/worker/dm-worker.toml ${TARGET}/conf/
else
    mv dm/master/task_basic.yaml ${TARGET}/conf/
    mv dm/master/task_advanced.yaml ${TARGET}/conf/
    mv dm/master/dm-master.toml ${TARGET}/conf/
    mv dm/worker/dm-worker.toml ${TARGET}/conf/
fi;
mv LICENSE ${TARGET}/

# start from v6.0.0(include v6.0.0), dm-ansible is removed, link https://github.com/pingcap/tiflow/pull/4917
# dm-master and dm-worker tiup pkg also need those config file even for version >=6.0.0
#  1. dm-master/conf/dm_worker.rules.yml
#  2. dm-master/scripts/DM-Monitor-Professional.json
#  3. dm-master/scripts/DM-Monitor-Standard.json
if [[ -d "dm/dm/dm-ansible" ]]; then
    mkdir -p ${TARGET}/dm-ansible
    cp -r dm/dm/dm-ansible/* ${TARGET}/dm-ansible/
else
    mkdir -p ${TARGET}/dm-ansible
    mkdir -p ${TARGET}/dm-ansible/conf
    mkdir -p ${TARGET}/dm-ansible/scripts
    cp -r dm/metrics/alertmanager/dm_worker.rules.yml ${TARGET}/dm-ansible/conf
    if [[ -d "dm/metrics/grafana" ]]; then
        cp -r dm/metrics/grafana/* ${TARGET}/dm-ansible/scripts
    else
        cp -r metrics/grafana/DM-* ${TARGET}/dm-ansible/scripts
    fi;
fi;
# start from v6.0.0(include v6.0.0), pingcap/dm-monitor-initializer is replace by pingcap/monitoring
# link https://github.com/pingcap/monitoring/pull/188.
if [[ -d "dm/dm/dm-ansible" ]]; then
    # mkdir -p ${TARGET}/monitoring/dashboards
    # mkdir -p ${TARGET}/monitoring/rules
    cd dm
    cp -f dm/dm-ansible/scripts/DM-Monitor-Professional.json monitoring/dashboards/
    cp -f dm/dm-ansible/scripts/DM-Monitor-Standard.json monitoring/dashboards/
    cp -f dm/dm-ansible/scripts/dm_instances.json monitoring/dashboards/
    mkdir -p monitoring/rules
    cp -f dm/dm-ansible/conf/dm_worker.rules.yml monitoring/rules/
    cd monitoring && go run dashboards/dashboard.go && cd ..
    cd ..
    mv dm/monitoring ${TARGET}/
fi;

if [[ ${ARCH} == "amd64" && ${RELEASE_TAG} < "v7.5.0" ]]; then
    curl http://download.pingcap.org/mydumper-latest-linux-amd64.tar.gz | tar xz
    mv mydumper-latest-linux-amd64/bin/mydumper bin/ && rm -rf mydumper-latest-linux-amd64
fi;
cp bin/* ${TARGET}/bin/
"""

buildsh["br"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
if [ ${failpoint} == 'true' ]; then
    make failpoint-enable
fi;
if [ ${REPO} == "tidb" ]; then
    make build_tools
else
    make build
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["dumpling"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
if [ ${REPO} == "tidb" ]; then
    make build_dumpling
else
    make build
fi;
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["ng-monitoring"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["tidb-enterprise-tools"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
go version
make syncer
make loader
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin/
"""

buildsh["tics"] = """
export CARGO_NET_GIT_FETCH_WITH_CLI=true
git config --global --add safe.directory '*'
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIFLASH_EDITION=Enterprise
fi;
if [ ${OS} == 'darwin' ]; then
    if [ ${ARCH} == "arm64" ]; then
        cd ..
        cp -f /Users/pingcap/birdstorm/fix-poco.sh ./
        cp -f /Users/pingcap/birdstorm/fix-libdaemon.sh ./
        ./fix-poco.sh
        ./fix-libdaemon.sh
        cd tics
    fi
    export PROTOC=/usr/local/bin/protoc
    export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
    mkdir -p release-darwin/build/
    [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
    [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
    [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
    [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
    chmod +x release-darwin/build/*
    ./release-darwin/build/build-release.sh
    ls -l ./release-darwin/tiflash/
    mv release-darwin ${TARGET}
else
    if [ "${params.USE_TIFLASH_RUST_CACHE}" == "true" ]; then
        mkdir -p ~/.cargo/registry
        mkdir -p ~/.cargo/git
        mkdir -p /rust/registry/cache
        mkdir -p /rust/registry/index
        mkdir -p /rust/git/db
        mkdir -p /rust/git/checkouts

        rm -rf ~/.cargo/registry/cache && ln -s /rust/registry/cache ~/.cargo/registry/cache
        rm -rf ~/.cargo/registry/index && ln -s /rust/registry/index ~/.cargo/registry/index
        rm -rf ~/.cargo/git/db && ln -s /rust/git/db ~/.cargo/git/db
        rm -rf ~/.cargo/git/checkouts && ln -s /rust/git/checkouts ~/.cargo/git/checkouts
    fi

    # check if LLVM toolchain is provided
    echo "the new parameter of tiflash debug is : ${params.TIFLASH_DEBUG}"

    # add a new condition branch for `release-linux-llvm` folder:
    if [[ -d "release-linux-llvm" && \$(which clang 2>/dev/null) ]]; then
        if [[ "${params.TIFLASH_DEBUG}" != 'true' ]]; then
            echo "start release ..........."
            NPROC=12 release-linux-llvm/scripts/build-release.sh
        else
            echo "start debug ..........."
            NPROC=12 release-linux-llvm/scripts/build-debug.sh
        fi
        mkdir -p ${TARGET}
        mv release-linux-llvm/tiflash ${TARGET}/tiflash
    elif [[ -d "release-centos7-llvm" && \$(which clang 2>/dev/null) ]]; then
        if [[ "${params.TIFLASH_DEBUG}" != 'true' ]]; then
            echo "start release ..........."
            NPROC=12 release-centos7-llvm/scripts/build-release.sh
        else
            echo "start debug ..........."
            NPROC=12 release-centos7-llvm/scripts/build-debug.sh
        fi
        mkdir -p ${TARGET}
        mv release-centos7-llvm/tiflash ${TARGET}/tiflash
    else
        if [[ "${params.TIFLASH_DEBUG}" != 'true' ]]; then
            echo "start release ..........."
            NPROC=12 release-centos7/build/build-release.sh
        else
            echo "start debug ..........."
            NPROC=12 release-centos7/build/build-debug.sh
        fi
        mkdir -p ${TARGET}
        mv release-centos7/tiflash ${TARGET}/tiflash
    fi
fi
rm -rf ${TARGET}/build-release || true
"""

buildsh["tikv"] = """
export CARGO_NET_GIT_FETCH_WITH_CLI=true
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
if [ ${EDITION} == 'enterprise' ]; then
    export TIKV_EDITION=Enterprise
    export ROCKSDB_SYS_SSE=0
fi;
if [ ${OS} == 'linux' ] && [ -f /opt/rh/devtoolset-8/enable ]; then
    source /opt/rh/devtoolset-8/enable
fi;
# compatibility: arm linux page sizes vary from 4k to 64k
if [ ${OS}/${ARCH} == 'linux/arm64' ]; then
    export JEMALLOC_SYS_WITH_LG_PAGE=16
fi;
if [ ${failpoint} == 'true' ]; then
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make fail_release
else
    CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
fi;
wait
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp bin/* ${TARGET}/bin
"""

buildsh["importer"] = """
if [ ${RELEASE_TAG}x != ''x ];then
    for a in \$(git tag --contains ${GIT_HASH}); do echo \$a && git tag -d \$a;done
    git tag -f ${RELEASE_TAG} ${GIT_HASH}
    git branch -D refs/tags/${RELEASE_TAG} || true
    git checkout -b refs/tags/${RELEASE_TAG}
fi;
echo using gcc 8
source /opt/rh/devtoolset-8/enable
if [[ ${ARCH} == 'arm64' ]]; then
    ROCKSDB_SYS_SSE=0 make release
else
    make release
fi
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp target/release/tikv-importer ${TARGET}/bin
"""

// NOTE: remove param --auto-push for pull-monitoring
//      we don't want to auto create pull request in repo https://github.com/pingcap/monitoring/pulls
buildsh["monitoring"] = """
go build -o pull-monitoring  cmd/monitoring.go
./pull-monitoring  --config=monitoring.yaml --tag=${RELEASE_TAG} --token=\$TOKEN
rm -rf ${TARGET}
mkdir -p ${TARGET}
mv monitor-snapshot/${RELEASE_TAG}/operator/* ${TARGET}
"""

buildsh["tiem"] = """
go version
make build
"""

buildsh["tidb-test"] = """
go version
if [ -d "partition_test/build.sh" ]; then
    cd partition_test
    bash build.sh
    cd ..
fi;
if [ -d "coprocessor_test/build.sh" ]; then
    cd coprocessor_test
    bash build.sh
    cd ..
fi;
if [ -d "concurrent-sql/build.sh" ]; then
    cd concurrent-sql
    bash build.sh
    cd ..
fi;
"""

buildsh["enterprise-plugin"] = """
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
rm -rf ${TARGET}
mkdir -p ${TARGET}/bin
cp whitelist/whitelist-1.so.md5 ${TARGET}/bin
cp whitelist/whitelist-1.so ${TARGET}/bin
cp audit/audit-1.so.md5 ${TARGET}/bin
cp audit/audit-1.so ${TARGET}/bin
"""

def usePod(){
    return OS=="linux"
}

def upload_fileserver(local, remote){
    if(usePod()){
        container("ks3util"){
            withCredentials([file(credentialsId: 'ks3util-secret-config', variable: 'KS3UTIL_CONF')]) {
                sh "ks3util -c \$KS3UTIL_CONF cp --loglevel=debug -f $local ks3://ee-fileserver/download/${remote}"
            }
        }
    }else{
        withCredentials([file(credentialsId: 'ks3util-secret-config', variable: 'KS3UTIL_CONF')]) {
            sh "ks3util -c \$KS3UTIL_CONF cp --loglevel=debug -f $local ks3://ee-fileserver/download/${remote}"
        }
    }
}

def download_fileserver(remote, local){
    if(usePod()){
        container("ks3util"){
            withCredentials([file(credentialsId: 'ks3util-secret-config', variable: 'KS3UTIL_CONF')]) {
                sh "ks3util -c \$KS3UTIL_CONF cp --loglevel=debug -f ks3://ee-fileserver/download/${remote} $local"
            }
        }
    }else{
        withCredentials([file(credentialsId: 'ks3util-secret-config', variable: 'KS3UTIL_CONF')]) {
            sh "ks3util -c \$KS3UTIL_CONF cp --loglevel=debug -f ks3://ee-fileserver/download/${remote} $local"
        }
    }
}

def packageBinary() {
    // 是否和代码一起打包，可以手动设置 NEED_SOURCE_CODE=true
    if (params.NEED_SOURCE_CODE) {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        """
    } else if (PRODUCT == "tiem") {
        sh """
        tar --exclude=${TARGET}.tar.gz -czvf ${TARGET}.tar.gz *
        """
    } else {
        sh """
        WORKDIR=\$(pwd)
        cd ${TARGET}
        tar  -czvf \$WORKDIR/${TARGET}.tar.gz *
        cd \$WORKDIR
        """
    }
    sh "sha256sum ${TARGET}.tar.gz | cut -d ' ' -f 1 >${TARGET}.tar.gz.sha256"
    // replace curl -F ${OUTPUT_BINARY}=@${TARGET}.tar.gz ${FILE_SERVER_URL}/upload
    upload_fileserver("${TARGET}.tar.gz", OUTPUT_BINARY)
    upload_fileserver("${TARGET}.tar.gz.sha256", "${OUTPUT_BINARY}.sha256")
}

def release(product, label) {
    checkoutStartTimeInMillis = System.currentTimeMillis()
    if (label != '') {
        container(label) {
            checkoutCode()
        }
    } else {
        checkoutCode()
    }
    checkoutFinishTimeInMillis = System.currentTimeMillis()

    if (PRODUCT == 'tics' && params.OS != 'darwin') {
        if (fileExists('release-linux-llvm/scripts/build-release.sh') || fileExists('release-centos7-llvm/scripts/build-release.sh')) {
            def image_tag_suffix = ""
            if (fileExists(".toolchain.yml")) {
                def config = readYaml(file: ".toolchain.yml")
                image_tag_suffix = config.image_tag_suffix
            }
            label = "tiflash-llvm${image_tag_suffix}".replaceAll('\\.', '-')
        } else if (fileExists('release-centos7/Makefile')){
            label = "tiflash"
        }
    }

    if (label != '') {
        container(label) {
            do_release(product)
        }
    } else {
        do_release(product)
    }
}

def do_release(product){
    withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
        compileStartTimeInMillis = System.currentTimeMillis()
        def cmd = buildsh[product]
        if (params.BUILD_ENV){
            cmd = "export ${params.BUILD_ENV};\n"+cmd
        }
        sh cmd
        compileFinishTimeInMillis = System.currentTimeMillis()
    }
    uploadStartTimeInMillis = System.currentTimeMillis()
    packageBinary()
    uploadFinishTimeInMillis = System.currentTimeMillis()
}

def run_with_arm_go_pod(Closure body) {
    switch(goVersion) {
        case "go1.13":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.13-arm64:latest"
            break
        case "go1.16":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.16-arm64:latest"
            break
        case "go1.18":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.18-arm64:latest"
            break
        case "go1.19":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.19-arm64:latest"
            break
        case "go1.20":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.20-arm64:latest"
            break
        case "go1.21":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.21-arm64:latest"
            break
        case "go1.23":
            arm_go_pod_image = "hub.pingcap.net/jenkins/centos7_golang-1.23-arm64:latest"
            break
        default:
            println "invalid go version ${goVersion}"
            break
    }
    if (PRODUCT == "tikv"){
        arm_go_pod_image="hub.pingcap.net/ee/ci/release-build-base-tikv:v2025.11.7"
    }
    run_with_pod(arm_go_pod_image, body)
}

def run_with_pod(String builder, Closure body) {
    def cloud = "kubernetes"
    def nodeSelector = "kubernetes.io/arch=${params.ARCH}"
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def namespace = "jenkins-cd"
    def builderRequestCpu="4"
    def buidlerRequestMemory="8Gi"
    if (PRODUCT in ["tikv", "tiflash","tics"] ){
        builderRequestCpu="16"
        buidlerRequestMemory="32Gi"
    }
    def cargo_pvc = ""
    if (ARCH == 'arm64'){
        cargo_pvc = 'cargo-home-linux-arm64'
    }else if (ARCH == 'amd64') {
        cargo_pvc = 'cargo-home-linux-amd64'
    }
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            nodeSelector: nodeSelector,
            containers: [
                    containerTemplate(
                            name: 'builder', alwaysPullImage: true,
                            image: "${builder}", ttyEnabled: true,
                            resourceRequestCpu: builderRequestCpu, resourceRequestMemory: buidlerRequestMemory,
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [
                                containerEnvVar(key: 'GOPATH', value: '/go'),
                                containerEnvVar(key: 'CARGO_HOME', value: '/var/cache/cargohome')
                            ],
                    ),
                    containerTemplate(
                            name: 'ks3util', alwaysPullImage: true,
                            image: "hub.pingcap.net/jenkins/ks3util:v2.4.2", ttyEnabled: true,
                            command: '/bin/sh -c', args: 'cat',
                            resourceRequestCpu: '100m', resourceRequestMemory: '256Mi',
                    ),
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false),
                    persistentVolumeClaim(mountPath:'/var/cache/cargohome', claimName: cargo_pvc)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            container("builder") {
                body()
            }
        }
    }
}

try {
    stage("Build ${PRODUCT}") {
        if (!ifFileCacheExists()) {
            if (params.BUILDER_IMG && params.OS=="linux"){
                run_with_pod(params.BUILDER_IMG,{
                        dir("go/src/github.com/pingcap/${PRODUCT}") {
                        deleteDir()
                        release(PRODUCT, 'builder')
                    }
                })
            }else if (useArmPodTemplate) {
                run_with_arm_go_pod{
                    dir("go/src/github.com/pingcap/${PRODUCT}") {
                        deleteDir()
                        release(PRODUCT, 'builder')
                    }
                }
            } else {
                node(nodeLabel) {
                    dir("go/src/github.com/pingcap/${PRODUCT}") {
                        deleteDir()
                        release(PRODUCT, containerLabel)
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (e) {
    println "error: ${e}"
    currentBuild.result = "FAILURE"
    throw e
} finally {
    println "done"
    def repo_owner = "pingcap"
    if (REPO == "tikv" || REPO == "importer" || REPO == "pd") {
        repo_owner = "tikv"
    }
    stage("Upload pipeline run data") {
        taskFinishTimeInMillis = System.currentTimeMillis()
        compile_duration = compileFinishTimeInMillis - compileStartTimeInMillis
        compile_duration_seconds = compile_duration / 1000
        checkout_duration = checkoutFinishTimeInMillis - checkoutStartTimeInMillis
        checkout_duration_seconds = checkout_duration / 1000
        upload_duration = uploadFinishTimeInMillis - uploadStartTimeInMillis
        upload_duration_seconds = upload_duration / 1000

        build job: 'upload-build-common-data-to-db',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_TYPE', value: "build binary"],
                    [$class: 'StringParameterValue', name: 'STATUS', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'OWNER', value: repo_owner],
                    [$class: 'StringParameterValue', name: 'REPO', value: "${REPO}"],
                    [$class: 'StringParameterValue', name: 'PRODUCT', value: "${PRODUCT}"],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: "${TARGET_BRANCH}"],
                    [$class: 'StringParameterValue', name: 'PR_NUMBER', value: "${GIT_PR}"],
                    [$class: 'StringParameterValue', name: 'COMMIT_ID', value: "${GIT_HASH}"],
                    [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                    [$class: 'StringParameterValue', name: 'OS_ARCH', value: "${OS}-${ARCH}"],
                    [$class: 'StringParameterValue', name: 'EDITION', value: "${EDITION}"],
                    [$class: 'StringParameterValue', name: 'FORCE_REBUILD', value: "${FORCE_REBUILD}"],
                    [$class: 'StringParameterValue', name: 'NEED_SOURCE_CODE', value: "${NEED_SOURCE_CODE}"],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_ID', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'JENKINS_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_REVOKER', value: "sre-bot"],
                    [$class: 'StringParameterValue', name: 'ERROR_CODE', value: "0"],
                    [$class: 'StringParameterValue', name: 'ERROR_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_END_TIME', value: "${taskFinishTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'CLONE_CODE_DURATION', value: "${checkout_duration_seconds}"],
                    [$class: 'StringParameterValue', name: 'COMPILE_DURATION', value: "${compile_duration_seconds}"],
                    [$class: 'StringParameterValue', name: 'UPLOAD_PACKAGE_DURATION', value: "${upload_duration_seconds}"],
                    [$class: 'StringParameterValue', name: 'BINARY_DOWNLOAD_URL', value: "${FILE_SERVER_URL}/download/${OUTPUT_BINARY}"],
            ]
    }
}
