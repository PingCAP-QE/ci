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
def os = "linux"
def arch = "arm64"

try {
    node("arm") {
        def ws = pwd()
        deleteDir()

        stage("GO node") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
            if(PRE_RELEASE == "false") {
                TIDB_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TIKV_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                PD_HASH = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                BINLOG_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                LIGHTNING_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-lightning -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TOOLS_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                CDC_HASH = sh(returnStdout: true, script: "python gethash.py -repo=ticdc -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                BR_HASH = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                IMPORTER_HASH = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TIFLASH_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                DUMPLING_HASH = sh(returnStdout: true, script: "python gethash.py -repo=dumpling -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            } else if(TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || LIGHTNING_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "PRE_RELEASE must be used with githash."
                sh """
                    exit 2
                """
            }
            TIDB_CTL_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb-ctl -version=master -s=${FILE_SERVER_URL}").trim()
        }

        stage("Build tidb-ctl") {
            dir("go/src/github.com/pingcap/tidb-ctl") {

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_CTL_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-ctl.git']]]
                }

                def target = "tidb-ctl-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-ctl/${TIDB_CTL_HASH}/centos7/tidb-ctl-${os}-${arch}.tar.gz"

                sh """
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                go version
                go build -o tidb-ctl
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp tidb-ctl ${target}/bin/
                tar czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb") {
            dir("go/src/github.com/pingcap/tidb") {

                def target = "tidb-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb/${TIDB_HASH}/centos7/tidb-server-${os}-${arch}.tar.gz"

                retry(20) {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'], [$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb.git']]]
                    }
                }

                sh """
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                make clean
                git checkout .
                go version
                make
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb-binlog") {
            dir("go/src/github.com/pingcap/tidb-binlog") {

                def target = "tidb-binlog-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-binlog/${BINLOG_HASH}/centos7/tidb-binlog-${os}-${arch}.tar.gz"


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
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                make clean
                go version
                make
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build tidb-lightning") {
            dir("go/src/github.com/pingcap/tidb-lightning") {

                def target = "tidb-lightning-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tidb-lightning/${LIGHTNING_HASH}/centos7/tidb-lightning-${os}-${arch}.tar.gz"

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
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
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
                def filepath = "builds/pingcap/tidb-tools/${TOOLS_HASH}/centos7/tidb-tools-${os}-${arch}.tar.gz"

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
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
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
                def filepath = "builds/pingcap/pd/${PD_HASH}/centos7/pd-server-${os}-${arch}.tar.gz"

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
                # pd golang 1.12
                export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                go version
                make
                make tools
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
            stage("Build cdc") {
                dir("go/src/github.com/pingcap/ticdc") {

                    def target = "ticdc-${os}-${arch}"
                    def filepath = "builds/pingcap/ticdc/${CDC_HASH}/centos7/ticdc-${os}-${arch}.tar.gz"


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
                    export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                    make build
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
                        filepath = "builds/pingcap/br/master/${BR_HASH}/centos7/br-${os}-${arch}.tar.gz"
                    } else {
                        filepath = "builds/pingcap/br/${RELEASE_TAG}/${BR_HASH}/centos7/br-${os}-${arch}.tar.gz"
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
                    # git checkout ${BR_HASH}
                    export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
                    GOPROXY=http://goproxy.pingcap.net,https://goproxy.cn make build
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp bin/* ${target}/bin
                    tar -czvf ${target}.tar.gz ${target}
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }

        if(RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.2") {
            stage("Build dumpling") {
                dir("go/src/github.com/pingcap/dumpling") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    def target = "dumpling-${RELEASE_TAG}-${os}-${arch}"
                    def filepath = "builds/pingcap/dumpling/${DUMPLING_HASH}/centos7/dumpling-${os}-${arch}.tar.gz"

                    if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${DUMPLING_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/dumpling.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/dumpling.git']]]
                    }

                    sh """
                    export PATH=/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin:${GO_BIN_PATH}
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

        stage("Build TiKV") {
            dir("go/src/github.com/pingcap/tikv") {
                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }

                def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tikv/${TIKV_HASH}/centos7/tikv-server-${os}-${arch}.tar.gz"

                if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                } else {
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true, timeout: 60]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/tikv.git']]]
                }

                sh """
                grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                    echo using gcc 8
                    source /opt/rh/devtoolset-8/enable
                fi
                CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 ROCKSDB_SYS_SSE=0 make dist_release
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp bin/* ${target}/bin
                tar czvf ${target}.tar.gz ${target}
                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                """
            }
        }

        stage("Build Importer") {
            dir("go/src/github.com/pingcap/importer") {

                def target = "importer-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/importer/${IMPORTER_HASH}/centos7/importer-${os}-${arch}.tar.gz"

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
                grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                    echo using gcc 8
                    source /opt/rh/devtoolset-8/enable
                fi
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

    if(SKIP_TIFLASH == "false" && (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v3.1.0")) {
        podTemplate(cloud: 'kubernetes-arm64', name: "build-arm-tiflash", label: "build-arm-tiflash",
                instanceCap: 5, idleMinutes: 30,
                workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                containers: [
                        containerTemplate(name: 'tiflash-build-arm', image: 'hub.pingcap.net/tiflash/tiflash-builder:arm64',
                                alwaysPullImage: true, ttyEnabled: true, privileged: true, command: 'cat',
                                resourceRequestCpu: '12000m', resourceRequestMemory: '20Gi',
                                resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
                        containerTemplate(name: 'jnlp', image: 'hub.pingcap.net/jenkins/jnlp-slave-arm64:0.0.1'),
                ])  {
            node("build-arm-tiflash") {
                container("tiflash-build-arm") {
                    stage("TiFlash build node") {
                        println "arm debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    }

                    stage("build tiflash") {
                        dir("tics") {

                            def target = "tiflash-${RELEASE_TAG}-${os}-${arch}"
                            def filepath

                            if(RELEASE_TAG == "nightly") {
                                filepath = "builds/pingcap/tiflash/master/${TIFLASH_HASH}/centos7/tiflash-${os}-${arch}.tar.gz"
                            } else {
                                filepath = "builds/pingcap/tiflash/${RELEASE_TAG}/${TIFLASH_HASH}/centos7/tiflash-${os}-${arch}.tar.gz"
                            }

                            retry(20) {
                                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }

                                if(PRE_RELEASE == "true" || RELEASE_TAG == "nightly") {
                                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                                } else {
                                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'LocalBranch'], [$class: 'CloneOption', noTags: true, timeout: 60], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tics.git']]]
                                }
                            }

                            sh """
                                NPROC=12 release-centos7/build/build-release.sh
                                cd release-centos7/
                                mv tiflash ${target}
                                tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                                """
                        }
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