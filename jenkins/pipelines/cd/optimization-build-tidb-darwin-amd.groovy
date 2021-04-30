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
* @PRE_RELEASE
*/
def slackcolor = 'good'
def os = "darwin"
def arch = "amd64"
def TIDB_CTL_HASH = "master"
def build_upload = { product, hash, binary ->
    stage("Build ${product}") {
        def repo = "git@github.com:pingcap/${product}.git"
        dir("go/src/github.com/pingcap/${product}") {
            retry(20) {
                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${hash}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: "${repo}"]]]
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
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin
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
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin
                    if [ ${product} != "pd" ]; then
                        make clean
                    fi;
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
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin
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

try {
    node("mac") {
        def ws = pwd()
        deleteDir()

        stage("GO node") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "build must be used with githash."
                sh "exit 2"
            }
        }
        if (BUILD_TIKV_IMPORTER == "false") {
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

            parallel builds
        }
        // 当前的 mac 用的是 gcc8
        stage("Build TiKV") {
            dir("go/src/github.com/pingcap/tikv") {
                def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tikv/optimization/${TIKV_HASH}/darwin/tikv-server.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                }
                if (BUILD_TIKV_IMPORTER == "false") {
                    sh """
                    for a in \$(git tag --contains ${TIKV_HASH}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${TIKV_HASH}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    """
                }
                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin:/usr/local/opt/binutils/bin/
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

        stage("Build Importer") {
            dir("go/src/github.com/pingcap/importer") {
                def target = "importer-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/importer/optimization/${IMPORTER_HASH}/darwin/importer.tar.gz"
                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${IMPORTER_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/importer.git']]]
                }
                if (BUILD_TIKV_IMPORTER == "false") {
                    sh """
                    for a in \$(git tag --contains ${IMPORTER_HASH}); do echo \$a && git tag -d \$a;done
                    git tag -f ${RELEASE_TAG} ${IMPORTER_HASH}
                    git branch -D refs/tags/${RELEASE_TAG} || true
                    git checkout -b refs/tags/${RELEASE_TAG}
                    """
                }
                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin:/usr/local/opt/binutils/bin/
                ROCKSDB_SYS_SSE=0 make release
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp target/release/tikv-importer ${target}/bin
                tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        if (SKIP_TIFLASH == "false" && BUILD_TIKV_IMPORTER == "false") {
            stage("build tiflash") {
                dir("tics") {
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
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/usr/local/go/bin:/usr/local/opt/binutils/bin/
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

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
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