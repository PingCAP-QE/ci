/*
* @TIDB_HASH
* @TIKV_HASH
* @PD_HASH
* @BINLOG_HASH
* @LIGHTNING_HASH
* @TOOLS_HASH
* @CDC_HASH
* @BR_HASH
* @IMPORTER_HASH
* @TIFLASH_HASH
* @RELEASE_TAG
* @FORCE_REBUILD
* @RELEASE_BRANCH
* @TIKV_PRID
*/
GO_BIN_PATH="/usr/local/go/bin"
def boolean tagNeedUpgradeGoVersion(String tag) {
    if (tag.startsWith("v") && tag > "v5.1") {
        println "tag=${tag} need upgrade go version"
        return true
    }
    return false
}

def isNeedGo1160 = tagNeedUpgradeGoVersion(RELEASE_TAG)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BIN_PATH="/usr/local/go1.16.4/bin"
} else {
    println "This build use go1.13"
}
println "GO_BIN_PATH=${GO_BIN_PATH}"


def slackcolor = 'good'
os = "darwin"
arch = "amd64"
platform = "darwin"
def TIDB_CTL_HASH = "master"

def checkIfFileCacheExists(product, hash, binary) {
    if (params.FORCE_REBUILD) {
        return false
    }
    if (!fileExists("gethash.py")) {
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    }
    def filepath = "builds/pingcap/${product}/optimization/${hash}/${platform}/${binary}.tar.gz"
    if (product == "br") {
        filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/${platform}/${binary}.tar.gz"
    }
    if (product == "ticdc") {
        filepath = "builds/pingcap/${product}/optimization/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
    }
    if (product == "dumpling") {
        filepath = "builds/pingcap/${product}/optimization/${hash}/${platform}/${binary}-${os}-${arch}.tar.gz"
    }
    if (product == "tiflash") {
        filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/${platform}/${binary}.tar.gz"
    }

    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${filepath} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result == 0) {
        echo "file ${FILE_SERVER_URL}/download/${filepath} found in cache server,skip build again"
        return true
    }
    return false
}

def build_upload = { product, hash, binary ->
    stage("Build ${product}") {
        node("mac") {
            if (checkIfFileCacheExists(product, hash, binary)) {
                return
            }
            def repo = "git@github.com:pingcap/${product}.git"
            def workspace = WORKSPACE
            dir("${workspace}/go/src/github.com/pingcap/${product}") {
                try {
                    checkout changelog: false, poll: true,
                            scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false,
                                  extensions: [[$class: 'CheckoutOption', timeout: 30],
                                               [$class: 'CloneOption', timeout: 60],
                                               [$class: 'PruneStaleBranch'],
                                               [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                                  userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                       refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                       url          : "${repo}"]]]
                } catch (info) {
                    retry(10) {
                        echo "checkout failed, retry..."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: true,
                                scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false,
                                      extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                   [$class: 'CloneOption', timeout: 60],
                                                   [$class: 'PruneStaleBranch'],
                                                   [$class: 'CleanBeforeCheckout']], submoduleCfg: [],
                                      userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                           refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                           url          : "${repo}"]]]
                    }
                }
                if (product == "tidb-ctl") {
                    hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                def filepath = "builds/pingcap/${product}/optimization/${hash}/darwin/${binary}.tar.gz"
                if (product == "br") {
                    filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/darwin/${binary}.tar.gz"
                }
                def target = "${product}-${RELEASE_TAG}-${os}-${arch}"
                if (product == "ticdc") {
                    target = "${product}-${os}-${arch}"
                    filepath = "builds/pingcap/${product}/optimization/${hash}/darwin/${product}-${os}-${arch}.tar.gz"
                }
                if (product == "dumpling") {
                    filepath = "builds/pingcap/${product}/optimization/${hash}/darwin/${product}-${os}-${arch}.tar.gz"
                }
                if (product == "tidb-ctl") {
                    sh """
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    go build -o /Users/pingcap/binarys/${product}
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp /Users/pingcap/binarys/${product} ${target}/bin/            
                    """
                }

                if (product in ["tidb", "tidb-binlog", "pd"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    if [ ${product} != "pd" ]; then
                        make clean
                    fi;
                    git checkout .
                    make
                    if [ ${product} = "pd" ]; then
                        make tools;
                    fi;
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    if [ ${product} = "tidb" ]; then
                        cp /Users/pingcap/binarys/tidb-ctl ${target}/bin/
                    fi;
                    cp bin/* ${target}/bin
                    """
                }
                if (product in ["tidb-tools", "ticdc", "br", "dumpling"]) {
                    sh """
                    for a in \$(git tag --contains ${hash}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${hash}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    if [ ${product} = "tidb-tools" ]; then
                        make clean;
                    fi;  
                    make build
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    mv bin/* ${target}/bin/
                    """
                }
                sh """
                    tar czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }
    }
}

try {
    stage("Validating HASH") {
        node("mac") {
            def ws = pwd()
            deleteDir()
            println "${ws}"
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 ||
                    TIFLASH_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "build must be used with githash."
                sh "exit 2"
            }
        }
    }

    stage("Build") {
        builds = [:]
        
        builds["Build tidb-ctl"] = {
            build_upload("tidb-ctl", TIDB_CTL_HASH, "tidb-ctl")
        }
        builds["Build tidb"] = {
            build_upload("tidb", TIDB_HASH, "tidb-server")
        }
        builds["Build tidb-binlog"] = {
            build_upload("tidb-binlog", BINLOG_HASH, "tidb-binlog")
        }
        builds["Build tidb-tools"] = {
            build_upload("tidb-tools", TOOLS_HASH, "tidb-tools")
        }
        builds["Build pd"] = {
            build_upload("pd", PD_HASH, "pd-server")
        }
        builds["Build ticdc"] = {
            build_upload("ticdc", CDC_HASH, "ticdc")
        }
        builds["Build br"] = {
            build_upload("br", BR_HASH, "br")
        }
        builds["Build dumpling"] = {
            build_upload("dumpling", DUMPLING_HASH, "dumpling")
        }
        

        if (SKIP_TIFLASH == "false") {
            builds["Build tiflash"] = {
                stage("Build tiflash") {
                    node("mac-i5") {
                        dir("tics") {
                            if (checkIfFileCacheExists("tiflash", TIFLASH_HASH, "tiflash")) {
                                return
                            }
                            def target = "tiflash-${RELEASE_TAG}-${os}-${arch}"
                            def filepath = "builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${TIFLASH_HASH}/darwin/tiflash.tar.gz"
                            retry(20) {
                                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 60], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                            }
                            sh """
                            for a in \$(git tag --contains ${TIFLASH_HASH}); do echo \$a && git tag -d \$a;done
                            git tag -f ${RELEASE_TAG} ${TIFLASH_HASH}
                            git branch -D refs/tags/${RELEASE_TAG} || true
                            git checkout -b refs/tags/${RELEASE_TAG}
                            """
                            sh """
                            export GOPATH=/Users/pingcap/gopkg
                            export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}:/usr/local/opt/binutils/bin/
                            mkdir -p release-darwin/build/
                            [ -f "release-darwin/build/build-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-release.sh > release-darwin/build/build-release.sh
                            [ -f "release-darwin/build/build-cluster-manager.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-cluster-manager.sh > release-darwin/build/build-cluster-manager.sh
                            [ -f "release-darwin/build/build-tiflash-proxy.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-proxy.sh > release-darwin/build/build-tiflash-proxy.sh
                            [ -f "release-darwin/build/build-tiflash-release.sh" ] || curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/tiflash/build-tiflash-release.sh > release-darwin/build/build-tiflash-release.sh
                            chmod +x release-darwin/build/*
                            ./release-darwin/build/build-release.sh
                            ls -l ./release-darwin/tiflash/
                            """
                            sh """
                            cd release-darwin
                            mv tiflash ${target}
                            tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                            curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                            """
                        }
                    }
                }
            }
        }

        builds["Build tikv"] = {
            stage("Build tikv") {
                node("mac-i7") {
                    dir("go/src/github.com/pingcap/tikv") {
                        if (checkIfFileCacheExists("tikv", TIKV_HASH, "tikv-server")) {
                            return
                        }
                        def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                        def filepath = "builds/pingcap/tikv/optimization/${TIKV_HASH}/darwin/tikv-server.tar.gz"

                        def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
                        if (TIKV_PRID != null && TIKV_PRID != "") {
                            specStr = "+refs/pull/${TIKV_PRID}/*:refs/remotes/origin/pr/${TIKV_PRID}/*"
                        }
                        def branch = TIKV_HASH
                        if (RELEASE_BRANCH != null && RELEASE_BRANCH != "") {
                            branch =RELEASE_BRANCH
                        }

                        retry(20) {
                            if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
                        }
                        
                        sh """
                        git checkout -f ${TIKV_HASH}
                        for a in \$(git tag --contains ${TIKV_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${TIKV_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        """
                        
                        sh """
                        export GOPATH=/Users/pingcap/gopkg
                        export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}:/usr/local/opt/binutils/bin/
                        CARGO_TARGET_DIR=/Users/pingcap/.target ROCKSDB_SYS_STATIC=1 make dist_release
                        rm -rf ${target}
                        mkdir -p ${target}/bin
                        cp bin/* /Users/pingcap/binarys
                        cp bin/* ${target}/bin
                        tar -czvf ${target}.tar.gz ${target}
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }

        builds["Build importer"] = {
            stage("Build importer") {
                node("mac-i7") {
                    dir("go/src/github.com/pingcap/importer") {
                        if (checkIfFileCacheExists("importer", IMPORTER_HASH, "importer")) {
                            return
                        }
                        def target = "importer-${RELEASE_TAG}-${os}-${arch}"
                        def filepath = "builds/pingcap/importer/optimization/${IMPORTER_HASH}/darwin/importer.tar.gz"
                        retry(20) {
                            if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${IMPORTER_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/importer.git']]]
                        }
                        
                        sh """
                        for a in \$(git tag --contains ${IMPORTER_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${IMPORTER_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        """
                        
                        sh """
                        export GOPATH=/Users/pingcap/gopkg
                        export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}:/usr/local/opt/binutils/bin/
                        ROCKSDB_SYS_SSE=0 make release
                        rm -rf ${target}
                        mkdir -p ${target}/bin
                        cp target/release/tikv-importer ${target}/bin
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }

        parallel builds
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e ) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}

stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // if (currentBuild.result != "SUCCESS") {
    //     slackSend channel: '#jenkins-ci-build-critical', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    // }
}