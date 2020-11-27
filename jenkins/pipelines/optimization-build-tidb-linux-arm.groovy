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
def os = "linux"
def arch = "arm64"
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

            def target = "${product}-${RELEASE_TAG}-${os}-${arch}"
            def filepath = "builds/pingcap/${product}/${hash}/centos7/${binary}-${os}-${arch}.tar.gz"
            if (product == "br") {
                filepath = "builds/pingcap/${product}/${RELEASE_TAG}/${hash}/centos7/${binary}-${os}-${arch}.tar.gz"
            }
            if (product == "tidb-ctl") {
                sh """
                go build -o ${product}
                rm -rf ${target}
                mkdir -p ${target}/bin
                cp ${product} ${target}/bin/            
                """
            }
            if (product in ["tidb", "tidb-binlog", "tidb-lightning", "pd"]) {
                sh """
                    git tag -d ${RELEASE_TAG} || true
                    git tag ${RELEASE_TAG} ${hash}
                    if [ ${product} != "pd" ]; then
                        make clean
                    fi;
                    make
                    if [ ${product} = "pd" ]; then
                        make tools;
                    fi;
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp bin/* ${target}/bin
                """
            }
            if (product in ["tidb-tools", "ticdc", "br", "dumpling"]) {
                sh """
                    git tag -d ${RELEASE_TAG} || true
                    git tag ${RELEASE_TAG} ${hash}
                    if [ ${product} = "tidb-tools" ]; then
                        make clean;
                    fi;                    
                    make build
                    rm -rf ${target}
                    mkdir -p ${target}/bin
                    cp bin/* ${target}/bin/
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
    node("arm") {
        def ws = pwd()
        deleteDir()
        stage("GO node") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || LIGHTNING_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "build must be used with githash."
                sh "exit"
            }
        }
        if (BUILD_TIKV_IMPORTER == "false") {
            build_upload("tidb-ctl", TIDB_CTL_HASH, "tidb-ctl")
            build_upload("tidb", TIDB_HASH, "tidb-server")
            build_upload("tidb-binlog", BINLOG_HASH, "tidb-binlog")
            build_upload("tidb-lightning", LIGHTNING_HASH, "tidb-lightning")
            build_upload("tidb-tools", TOOLS_HASH, "tidb-tools")
            build_upload("pd", PD_HASH, "pd-server")
            build_upload("ticdc", CDC_HASH, "ticdc")
            build_upload("br", BR_HASH, "br")
            build_upload("dumpling", DUMPLING_HASH, "dumpling")
        }
        stage("Build TiKV") {
            dir("go/src/github.com/pingcap/tikv") {
                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                def target = "tikv-${RELEASE_TAG}-${os}-${arch}"
                def filepath = "builds/pingcap/tikv/${TIKV_HASH}/centos7/tikv-server-${os}-${arch}.tar.gz"
                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                if (BUILD_TIKV_IMPORTER == "false") {
                    sh "git tag -d ${RELEASE_TAG} || true"
                    sh "git tag ${RELEASE_TAG} ${TIKV_HASH}"
                }
                sh """
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
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${IMPORTER_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/importer.git']]]
                }
                if (BUILD_TIKV_IMPORTER == "false") {
                    sh "git tag -d ${RELEASE_TAG} || true"
                    sh "git tag ${RELEASE_TAG} ${IMPORTER_HASH}"
                }
                sh """
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

    if (SKIP_TIFLASH == "false" && BUILD_TIKV_IMPORTER == "false") {
        podTemplate(cloud: 'kubernetes-arm64', name: "build-arm-tiflash", label: "build-arm-tiflash",
                instanceCap: 5, idleMinutes: 30,
                workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                containers: [
                        containerTemplate(name: 'tiflash-build-arm', image: 'hub.pingcap.net/tiflash/tiflash-builder:arm64',
                                alwaysPullImage: true, ttyEnabled: true, privileged: true, command: 'cat',
                                resourceRequestCpu: '12000m', resourceRequestMemory: '20Gi',
                                resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
                        containerTemplate(name: 'jnlp', image: 'hub.pingcap.net/jenkins/jnlp-slave-arm64:0.0.1'),
                ]) {
            node("build-arm-tiflash") {
                container("tiflash-build-arm") {
                    stage("TiFlash build node") {
                        println "arm debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    }

                    stage("build tiflash") {
                        dir("tics") {
                            def target = "tiflash-${RELEASE_TAG}-${os}-${arch}"
                            def filepath = "builds/pingcap/tiflash/${RELEASE_TAG}/${TIFLASH_HASH}/centos7/tiflash-${os}-${arch}.tar.gz"
                            retry(20) {
                                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                            }
                            sh "git tag -d ${RELEASE_TAG} || true"
                            sh "git tag ${RELEASE_TAG} ${TIFLASH_HASH}"
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