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
def githash
def os = "linux"
def arch = "amd64"

// 为了和之前兼容，linux amd 的 build 和上传包的内容都采用 build_xxx_multi_branch 中的 build 脚本
// linux arm 和 Darwin amd 保持不变
try {
    node("build_go1130") {
        container("golang") {
            def ws = pwd()
            deleteDir()
            stage("GO node") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                println "${ws}"
                if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || LIGHTNING_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                    println "build must be used with githash."
                    sh "exit 2"
                }
            }
            if (BUILD_TIKV_IMPORTER == "false") {

                stage("Build tidb-ctl") {
                    dir("go/src/github.com/pingcap/tidb-ctl") {
                        deleteDir()
                        git credentialsId: 'github-sre-bot-ssh', url: "git@github.com:pingcap/tidb-ctl.git", branch: "master"
                        githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                        def target = "tidb-ctl"
                        def filepath = "builds/pingcap/tidb-ctl/optimization/${githash}/centos7/tidb-ctl.tar.gz"

                        sh """
                        go version
                        mkdir bin
                        go build -o bin/tidb-ctl
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz *
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build tidb") {
                    dir("go/src/github.com/pingcap/tidb") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "tidb-server"
                        def filepath = "builds/pingcap/tidb/optimization/${TIDB_HASH}/centos7/tidb-server.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                        sh """
                        mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        for a in \$(git tag --contains ${TIDB_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${TIDB_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        make clean
                        git checkout .
                        go version
                        GOPATH=${ws}/go WITH_RACE=1 make && mv bin/tidb-server bin/tidb-server-race
                        git checkout .
                        GOPATH=${ws}/go WITH_CHECK=1 make && mv bin/tidb-server bin/tidb-server-check
                        git checkout .
                        GOPATH=${ws}/go make failpoint-enable && make server && mv bin/tidb-server{,-failpoint} && make failpoint-disable
                        git checkout .
                        GOPATH=${ws}/go make server_coverage || true
                        git checkout .
                        GOPATH=${ws}/go make

                        if [ \$(grep -E "^ddltest:" Makefile) ]; then
                            git checkout .
                            GOPATH=${ws}/go make ddltest
                        fi
                        
                        if [ \$(grep -E "^importer:" Makefile) ]; then
                            git checkout .
                            GOPATH=${ws}/go make importer
                        fi
                        
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz *
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
                stage("Build plugins") {
                    dir("go/src/github.com/pingcap/tidb-build-plugin") {
                        deleteDir()
                        timeout(20) {
                            sh """
                       cp -R ${ws}/go/src/github.com/pingcap/tidb/. ./
                       cd cmd/pluginpkg
                       go build
                       """
                        }
                    }
                    dir("go/src/github.com/pingcap/enterprise-plugin") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                        def plugin_hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                    }
                    def filepath_whitelist = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/whitelist-1.so"
                    def md5path_whitelist = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/whitelist-1.so.md5"
                    def filepath_audit = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/audit-1.so"
                    def md5path_audit = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/audit-1.so.md5"
                    dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                        sh """
                   GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                   """
                        sh """
                            md5sum whitelist-1.so > whitelist-1.so.md5
                            curl -F ${md5path_whitelist}=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
                            curl -F ${filepath_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload
                            """
                    }
                    dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                        sh """
                   GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                   """
                        sh """
                        md5sum audit-1.so > audit-1.so.md5
                        curl -F ${md5path_audit}=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
                        curl -F ${filepath_audit}=@audit-1.so ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build tidb-binlog") {
                    dir("go/src/github.com/pingcap/tidb-binlog") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }

                        def target = "tidb-binlog"
                        def filepath = "builds/pingcap/tidb-binlog/optimization/${BINLOG_HASH}/centos7/tidb-binlog.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${BINLOG_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                        sh """
                        for a in \$(git tag --contains ${BINLOG_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${BINLOG_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        make clean
                        go version
                        make
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz bin/*
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

//                stage("Build tidb-lightning") {
//                    dir("go/src/github.com/pingcap/tidb-lightning") {
//                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
//                            deleteDir()
//                        }
//                        def target = "tidb-lightning"
//                        def filepath = "builds/pingcap/tidb-lightning/optimization/${LIGHTNING_HASH}/centos7/tidb-lightning.tar.gz"
//                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${LIGHTNING_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-lightning.git']]]
//                        sh """
//                        for a in \$(git tag --contains ${LIGHTNING_HASH}); do echo \$a && git tag -d \$a;done
//                        git tag -f ${RELEASE_TAG} ${LIGHTNING_HASH}
//                        git branch -D refs/tags/${RELEASE_TAG} || true
//                        git checkout -b refs/tags/${RELEASE_TAG}
//                        make clean
//                        go version
//                        make
//                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz bin/*
//                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
//                        """
//                    }
//                }

                stage("Build tidb-tools") {
                    dir("go/src/github.com/pingcap/tidb-tools") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "tidb-tools"
                        def filepath = "builds/pingcap/tidb-tools/optimization/${TOOLS_HASH}/centos7/tidb-tools.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TOOLS_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb-tools.git']]]
                        sh """
                        for a in \$(git tag --contains ${TOOLS_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${TOOLS_HASH}
                        make clean
                        go version
                        make build
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz bin/*
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build pd") {
                    dir("go/src/github.com/pingcap/pd") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "pd-server"
                        def filepath = "builds/pingcap/pd/optimization/${PD_HASH}/centos7/pd-server.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PD_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/pd.git']]]

                        sh """
                        for a in \$(git tag --contains ${PD_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${PD_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        go version
                        make
                        git checkout .
                        make tools
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz *
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build cdc") {
                    dir("go/src/github.com/pingcap/ticdc") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "ticdc-linux-amd64"
                        def filepath = "builds/pingcap/ticdc/optimization/${CDC_HASH}/centos7/ticdc-linux-amd64.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${CDC_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/ticdc.git']]]
                        sh """
                        for a in \$(git tag --contains ${CDC_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${CDC_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        make build
                        mkdir -p ${target}/bin
                        mv bin/cdc ${target}/bin/
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz ${target}
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build dumpling") {
                    dir("go/src/github.com/pingcap/dumpling") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def filepath = "builds/pingcap/dumpling/optimization/${DUMPLING_HASH}/centos7/dumpling.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${DUMPLING_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/dumpling.git']]]
                        sh """
                        for a in \$(git tag --contains ${DUMPLING_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${DUMPLING_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        make build
                        tar --exclude=dumpling.tar.gz -czvf dumpling.tar.gz *
                        curl -F ${filepath}=@dumpling.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }

                stage("Build br") {
                    dir("go/src/github.com/pingcap/br") {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "br"
                        def filepath = "builds/pingcap/br/optimization/${RELEASE_TAG}/${BR_HASH}/centos7/br.tar.gz"
                        def filepath2 = "builds/pingcap/br/optimization/${BR_HASH}/centos7/br.tar.gz"

                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${BR_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/br.git']]]

                        sh """
                        for a in \$(git tag --contains ${BR_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${BR_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        make build
                        tar --exclude=br.tar.gz -czvf br.tar.gz ./bin
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        curl -F ${filepath2}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    node("build") {
        container("rust") {
            stage("Rust node") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                deleteDir()
            }

            stage("Build TiKV") {
                dir("go/src/github.com/pingcap/tikv") {
                    if (BUILD_TIKV_IMPORTER == "true") {
                        dir("tikv_tmp") {
                            deleteDir()
                            sh """
                            curl -sL -o tikv-server.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/tikv/${TIKV_HASH}/centos7/tikv-server.tar.gz
                            curl -F builds/pingcap/tikv/optimization/${TIKV_HASH}/centos7/tikv-server.tar.gz=@tikv-server.tar.gz ${FILE_SERVER_URL}/upload
                            """
                        }
                    } else {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "tikv-server"
                        def filepath = "builds/pingcap/tikv/optimization/${TIKV_HASH}/centos7/tikv-server.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                        sh """
                        for a in \$(git tag --contains ${TIKV_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${TIKV_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                        fi
                        CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 make dist_release
                        tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz bin/*
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }

                }
            }

            stage("Build Importer") {
                dir("go/src/github.com/pingcap/importer") {
                    if (BUILD_TIKV_IMPORTER == "true") {
                        dir("importer_tmp") {
                            deleteDir()
                            sh """
                            curl -sL -o importer.tar.gz ${FILE_SERVER_URL}/download/builds/pingcap/importer/${IMPORTER_HASH}/centos7/importer.tar.gz
                            curl -F builds/pingcap/importer/optimization/${IMPORTER_HASH}/centos7/importer.tar.gz=@importer.tar.gz ${FILE_SERVER_URL}/upload
                            """
                        }
                    } else {
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "importer"
                        def filepath = "builds/pingcap/importer/optimization/${IMPORTER_HASH}/centos7/importer.tar.gz"
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${IMPORTER_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/importer.git']]]
                        sh """
                        for a in \$(git tag --contains ${IMPORTER_HASH}); do echo \$a && git tag -d \$a;done
                        git tag -f ${RELEASE_TAG} ${IMPORTER_HASH}
                        git branch -D refs/tags/${RELEASE_TAG} || true
                        git checkout -b refs/tags/${RELEASE_TAG}
                        grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                        if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                            echo using gcc 8
                            source /opt/rh/devtoolset-8/enable
                        fi
                        make release && mkdir -p bin/ && mv target/release/tikv-importer bin/
                        tar --exclude=${target}.tar.gz -czvf importer.tar.gz bin/*
                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }

    if (SKIP_TIFLASH == "false" && BUILD_TIKV_IMPORTER == "false") {
        podTemplate(name: "build-tiflash-release", label: "build-tiflash-release",
                nodeSelector: 'role_type=slave',
                instanceCap: 5, idleMinutes: 10,
                workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                containers: [
                        containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
                        containerTemplate(name: 'docker', image: 'hub.pingcap.net/zyguan/docker:build-essential',
                                alwaysPullImage: false, envVars: [
                                envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
                        ], ttyEnabled: true, command: 'cat'),
                        containerTemplate(name: 'builder', image: 'hub.pingcap.net/tiflash/tiflash-builder',
                                alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                                resourceRequestCpu: '12000m', resourceRequestMemory: '10Gi',
                                resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
                ]) {
            node("build-tiflash-release") {
                container("builder") {
                    stage("TiFlash build node") {
                        println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    }

                    stage("Build tiflash") {
                        dir("tics") {
                            if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            def target = "tiflash"
                            def filepath = "builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${TIFLASH_HASH}/centos7/tiflash.tar.gz"
                            def filepath2 = "builds/pingcap/tiflash/optimization/${TIFLASH_HASH}/centos7/tiflash.tar.gz"
                            retry(10) {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                            }

                            sh """
                                for a in \$(git tag --contains ${TIFLASH_HASH}); do echo \$a && git tag -d \$a;done
                                git tag -f ${RELEASE_TAG} ${TIFLASH_HASH}
                                git branch -D refs/tags/${RELEASE_TAG} || true
                                git checkout -b refs/tags/${RELEASE_TAG}
                                NPROC=12 release-centos7/build/build-release.sh
                                ls release-centos7/build-release/
                                ls release-centos7/tiflash/
                                cd release-centos7/
                                tar --exclude=${target}.tar.gz -czvf tiflash.tar.gz tiflash
                                curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                                curl -F ${filepath2}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
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