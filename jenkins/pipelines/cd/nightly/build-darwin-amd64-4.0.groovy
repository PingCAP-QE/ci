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
GO_BIN_PATH="/usr/local/go/bin"
def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    if (targetBranch == "nightly"){
        return true
    }
    return false
}

def isNeedGo1160 = isBranchMatched(["master", "release-5.1"], RELEASE_TAG)
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BIN_PATH="/usr/local/go1.16.4/bin"
} else {
    println "This build use go1.13"
}
println "GO_BIN_PATH=${GO_BIN_PATH}"


def slackcolor = 'good'
def githash
def os = "darwin"
def arch = "amd64"
def tag

try {
    node("mac") {
        def ws = pwd()
        deleteDir()

        stage("GO node") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            if(PRE_RELEASE == "false") {
                TIDB_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TIKV_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                PD_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                BINLOG_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                LIGHTNING_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tidb-lightning -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TOOLS_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tidb-tools -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()

                if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
                    CDC_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }

                if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0") {
                    BR_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
                // importer default branch is release-5.0
                if(RELEASE_TAG == "nightly") {
                    IMPORTER_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=importer -version=release-5.0 -s=${FILE_SERVER_URL}").trim()
                }else{
                    IMPORTER_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=importer -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }

                if(SKIP_TIFLASH == "false" && (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0")) {
                    TIFLASH_HASH = sh(returnStdout: true, script: "/usr/local/bin/python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }

                if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.2") {
                    DUMPLING_HASH = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                }
            } else if(TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || LIGHTNING_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "PRE_RELEASE must be used with githash."
                sh """
                    exit 2
                """
            }
            TIDB_CTL_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=nightly -s=${FILE_SERVER_URL}").trim()
        }

        stage("Build tidb-ctl") {
            dir("go/src/github.com/pingcap/tidb-ctl") {

                retry(20) {
                    deleteDir()
                    // git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap/tidb-ctl.git", branch: "master"
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_CTL_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-ctl.git']]]
                }

                def target = "tidb-ctl-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-ctl/${TIDB_CTL_HASH}/darwin/tidb-ctl.tar.gz"

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                go version
                go build -o /Users/pingcap/binarys/tidb-ctl

                rm -rf ${target}
                mkdir -p ${target}/bin
                cp /Users/pingcap/binarys/tidb-ctl ${target}/bin/
                tar -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb") {
            dir("go/src/github.com/pingcap/tidb") {

                def target = "tidb-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb/${TIDB_HASH}/darwin/tidb-server.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                
                make clean
                git checkout .
                go version
                make
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp /Users/pingcap/binarys/tidb-ctl ${target}/bin/
                cp bin/* ${target}/bin
                tar -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb-binlog") {
            dir("go/src/github.com/pingcap/tidb-binlog") {

                def target = "tidb-binlog-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-binlog/${BINLOG_HASH}/darwin/tidb-binlog.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${BINLOG_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb-binlog.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                make clean
                go version
                make

                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* /Users/pingcap/binarys
                cp bin/* ${target}/bin
                tar -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb-lightning") {
            dir("go/src/github.com/pingcap/tidb-lightning") {

                def target = "tidb-lightning-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-lightning/${LIGHTNING_HASH}/darwin/tidb-lightning.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${LIGHTNING_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-lightning.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb-lightning.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                make clean
                go version
                make
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb-tools") {
            dir("go/src/github.com/pingcap/tidb-tools") {

                def target = "tidb-tools-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-lightning/${TOOLS_HASH}/darwin/tidb-tools.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TOOLS_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-tools.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb-tools.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                make clean
                go version
                make build
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build pd") {
            dir("go/src/github.com/pingcap/pd") {

                def target = "pd-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/pd/${PD_HASH}/darwin/pd-server.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PD_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/pd.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/pd.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/bin:/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                go version
                make
                make tools

                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* /Users/pingcap/binarys
                cp bin/* ${target}/bin
                tar -czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
            stage("Build cdc") {
                dir("go/src/github.com/pingcap/ticdc") {

                    def target = "ticdc-${os}-${arch}"
                    def filepath = "builds/pingcap/ticdc/${CDC_HASH}/darwin/ticdc-${os}-${arch}.tar.gz"

                    retry(20) {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${CDC_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/ticdc.git']]]
                        } else {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/ticdc.git']]]
                        }
                    }

                    sh """
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    go version
                    mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                    GOPATH=\$GOPATH:${ws}/go make build
                    mkdir -p ${target}/bin
                    mv bin/cdc ${target}/bin/
                    tar -czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }

        if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0") {
            stage("Build br") {
                dir("go/src/github.com/pingcap/br") {

                    def target = "br-${RELEASE_TAG}-${os}-${arch}"
                    def filepath
                    if(RELEASE_TAG == "nightly") {
                        filepath = "builds/pingcap/br/master/${BR_HASH}/darwin/br.tar.gz"
                    } else {
                        filepath = "builds/pingcap/br/${RELEASE_TAG}/${BR_HASH}/darwin/br.tar.gz"
                    }

                    retry(20) {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${BR_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/br.git']]]
                        } else {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/br.git']]]
                        }
                    }

                    sh """
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    #GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn make build
                    make build
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp bin/* ${target}/bin
                    tar --exclude=br.tar.gz -czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }

        if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.2") {
            stage("Build dumpling") {
                dir("go/src/github.com/pingcap/dumpling") {

                    def target = "dumpling-${RELEASE_TAG}-${os}-${arch}"
                    def filepath = "builds/pingcap/dumpling/${DUMPLING_HASH}/darwin/dumpling-${os}-${arch}.tar.gz"

                    retry(20) {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${DUMPLING_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/dumpling.git']]]
                        } else {
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/dumpling.git']]]
                        }
                    }

                    sh """
                    export GOPATH=/Users/pingcap/gopkg
                    export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:${GO_BIN_PATH}
                    make build
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp bin/* ${target}/bin
                    tar -czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
//当前的 mac 环境用的是 gcc8
        stage("Build TiKV") {
            dir("go/src/github.com/pingcap/tikv") {

                def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tikv/${TIKV_HASH}/darwin/tikv-server.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/tikv.git']]]
                    }
                }

                sh """
                export GOPATH=/Users/pingcap/gopkg
                export PATH=/Users/pingcap/.cargo/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Users/pingcap/.cargo/bin:/${GO_BIN_PATH}:/usr/local/opt/binutils/bin/
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
                def filepath = "builds/pingcap/importer/${IMPORTER_HASH}/darwin/importer.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${IMPORTER_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/importer.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/importer.git']]]
                    }
                }

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

        if(SKIP_TIFLASH == "false" && (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0")) {
            node("mac-i5"){
                stage("build tiflash") {
                    dir("tics") {

                        def target = "tiflash-${RELEASE_TAG}-${os}-${arch}"
                        def filepath

                        if(RELEASE_TAG == "nightly") {
                            filepath = "builds/pingcap/tiflash/master/${TIFLASH_HASH}/darwin/tiflash.tar.gz"
                        } else {
                            filepath = "builds/pingcap/tiflash/${RELEASE_TAG}/${TIFLASH_HASH}/darwin/tiflash.tar.gz"
                        }

                        retry(20) {
                            if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class:'CheckoutOption', timeout: 30], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 60], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                            } else {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class:'CheckoutOption', timeout: 30], [$class: 'LocalBranch'], [$class: 'CloneOption', noTags: true, timeout: 60], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tics.git']]]
                            }
                        }

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