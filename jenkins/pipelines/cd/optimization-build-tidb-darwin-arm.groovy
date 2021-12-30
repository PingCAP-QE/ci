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
* @NGMonitoring_HASH
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
arch = "arm64"
platform = "${os}-${arch}"
def TIDB_CTL_HASH = "master"

def libs

try {
    stage("Validating HASH") {
        node("mac-arm") {
            def ws = pwd()
            deleteDir()
            println "${ws}"
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 ||
                    TIFLASH_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "build must be used with githash."
                sh "exit 2"
            }
            if (IMPORTER_HASH.length() < 40 && RELEASE_TAG < "v5.2.0"){
                println "build must be used with githash."
                sh "exit 2"
            }
            checkout scm
            libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
        }
    }

    stage("Build") {
        build_para = [:]
        build_para["tidb-ctl"] = TIDB_CTL_HASH
        build_para["tidb"] = TIDB_HASH
        build_para["tidb-binlog"] = BINLOG_HASH
        build_para["tidb-tools"] = TOOLS_HASH
        build_para["pd"] = PD_HASH
        build_para["ticdc"] = CDC_HASH
        build_para["br"] = BR_HASH
        build_para["dumpling"] = DUMPLING_HASH
        build_para["ng-monitoring"] = NGMonitoring_HASH
        build_para["FORCE_REBUILD"] = params.FORCE_REBUILD
        build_para["RELEASE_TAG"] = RELEASE_TAG
        build_para["PLATFORM"] = platform
        build_para["OS"] = os
        build_para["ARCH"] = arch
        build_para["FILE_SERVER_URL"] = FILE_SERVER_URL
        build_para["GIT_PR"] = ""

        builds = libs.create_builds(build_para)
        

        if (SKIP_TIFLASH == "false") {
            builds["Build tiflash"] = {
                stage("Build tiflash") {
                    node("mac-arm-tiflash") {
                        def ws = pwd()
                        dir("tics") {
                            // if (!params.FORCE_REBUILD && libs.checkIfFileCacheExists("tiflash", TIFLASH_HASH, "tiflash")) {
                            //     return
                            // }
                            deleteDir()
                            def target = "tiflash-${RELEASE_TAG}-${os}-${arch}"
                            def filepath = "builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${TIFLASH_HASH}/${platform}/tiflash.tar.gz"
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
                            cp -f /Users/pingcap/birdstorm/fix-poco.sh ${ws}
                            cp -f /Users/pingcap/birdstorm/fix-libdaemon.sh ${ws}
                            ${ws}/fix-poco.sh
                            ${ws}/fix-libdaemon.sh
                            export GOPATH=/Users/pingcap/gopkg
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
                node("mac-arm") {
                    dir("go/src/github.com/pingcap/tikv") {
                        // if (!params.FORCE_REBUILD && libs.checkIfFileCacheExists("tikv", TIKV_HASH, "tikv-server")) {
                        //     return
                        // }
                        deleteDir()
                        def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                        def filepath = "builds/pingcap/tikv/optimization/${RELEASE_TAG}/${TIKV_HASH}/${platform}/tikv-server.tar.gz"

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
                        export PROTOC=/usr/local/bin/protoc
                        export GOPATH=/Users/pingcap/gopkg
                        export PATH=/usr/local/opt/binutils/bin:/usr/local/bin:/Users/pingcap/.cargo/bin:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${GO_BIN_PATH}
                        CARGO_TARGET_DIR=/Users/pingcap/.target ROCKSDB_SYS_STATIC=1 ROCKSDB_SYS_SSE=0 make dist_release
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
}