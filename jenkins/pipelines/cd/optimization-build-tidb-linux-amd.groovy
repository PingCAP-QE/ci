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
* @FORCE_REBUILD
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
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

def TIDB_CTL_HASH = "master"

def slackcolor = 'good'
def githash
os = "linux"
arch = "amd64"
platform = "centos7"
def libs

// 为了和之前兼容，linux amd 的 build 和上传包的内容都采用 build_xxx_multi_branch 中的 build 脚本
// linux arm 和 Darwin amd 保持不变
try {

    // stage prepare
    // stage build
    stage("Validating HASH") {
        node("${GO_BUILD_SLAVE}") {
            container("golang") {
                def ws = pwd()
                deleteDir()
                stage("GO node") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "${ws}"
                    if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                        println "build must be used with githash."
                        sh "exit 2"
                    }
                    if (IMPORTER_HASH.length() < 40 && RELEASE_TAG < "v5.2.0"){
                        println "build must be used with githash."
                    sh "exit 2"
                    }
                }
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
            }
        }
    }

    stage("Build") {
        build_para = [:]
        build_para["tidb-ctl"] = TIDB_CTL_HASH
        build_para["tidb"] = TIDB_HASH
        build_para["tikv"] = TIKV_HASH
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
        // TODO: refine tidb & plugin builds
        builds["Build tidb && plugins"] = {
            node("${GO_BUILD_SLAVE}") {
                // if (ifFileCacheExists("tidb",TIDB_HASH,"tidb-server")){
                //     return
                // }
                def ws = pwd()
                deleteDir()
                // update code
                dir("/home/jenkins/agent/code-archive") {
                    // delete to clean workspace in case of agent pod reused lead to conflict.
                    deleteDir()
                    // copy code from nfs cache
                    container("golang") {
                        if (fileExists("/nfs/cache/git-test/src-tidb.tar.gz")) {
                            timeout(5) {
                                sh """
                                    cp -R /nfs/cache/git-test/src-tidb.tar.gz*  ./
                                    mkdir -p ${ws}/go/src/github.com/pingcap/tidb
                                    tar -xzf src-tidb.tar.gz -C ${ws}/go/src/github.com/pingcap/tidb --strip-components=1
                                """
                            }
                        }
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/tidb"
                        echo "Clean dir then get tidb src code from fileserver"
                        deleteDir()
                    }
                    if (!fileExists("${ws}/go/src/github.com/pingcap/tidb/Makefile")) {
                        dir("${ws}/go/src/github.com/pingcap/tidb") {
                            sh """
                                rm -rf /home/jenkins/agent/code-archive/tidb.tar.gz
                                rm -rf /home/jenkins/agent/code-archive/tidb
                                wget -O /home/jenkins/agent/code-archive/tidb.tar.gz  ${FILE_SERVER_URL}/download/source/tidb.tar.gz -q --show-progress
                                tar -xzf /home/jenkins/agent/code-archive/tidb.tar.gz -C ./ --strip-components=1
                            """
                        }
                    }

                    try {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                        }

                    }
                }
                dir("${ws}/go/src/github.com/pingcap/tidb") {
                    container("golang") {
                        def target = "tidb-server"
                        def filepath = "builds/pingcap/tidb/optimization/${RELEASE_TAG}/${TIDB_HASH}/centos7/tidb-server.tar.gz"
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
                echo "Build tidb plugins"
                dir("${ws}/go/src/github.com/pingcap/tidb-build-plugin") {
                    container("golang") {
                        timeout(20) {
                            sh """
                                cp -R ${ws}/go/src/github.com/pingcap/tidb/. ./
                                cd cmd/pluginpkg
                                go build
                            """
                        }
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/enterprise-plugin") {
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_BRANCH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                    def plugin_hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                def filepath_whitelist = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/whitelist-1.so"
                def md5path_whitelist = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/whitelist-1.so.md5"
                def filepath_audit = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/audit-1.so"
                def md5path_audit = "builds/pingcap/tidb-plugins/optimization/${RELEASE_TAG}/centos7/audit-1.so.md5"
                dir("${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                    container("golang") {
                        sh """
                            go mod tidy
                            GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                        """
                        sh """
                            md5sum whitelist-1.so > whitelist-1.so.md5
                            curl -F ${md5path_whitelist}=@whitelist-1.so.md5 ${FILE_SERVER_URL}/upload
                            curl -F ${filepath_whitelist}=@whitelist-1.so ${FILE_SERVER_URL}/upload
                        """
                    }
                }
                dir("${ws}/go/src/github.com/pingcap/enterprise-plugin/audit") {
                    container("golang") {
                        sh """
                            go mod tidy
                            GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb-build-plugin/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                        """
                        sh """
                            md5sum audit-1.so > audit-1.so.md5
                            curl -F ${md5path_audit}=@audit-1.so.md5 ${FILE_SERVER_URL}/upload
                            curl -F ${filepath_audit}=@audit-1.so ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }

        if (SKIP_TIFLASH == "false") {
            builds["Build tiflash release"] = {
                podTemplate(name: "build-tiflash-release", label: "build-tiflash-release",
                        nodeSelector: 'role_type=slave', instanceCap: 5,
                        workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                        containers: [
                                containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
                                containerTemplate(name: 'docker', image: 'hub.pingcap.net/zyguan/docker:build-essential',
                                        alwaysPullImage: false, envVars: [envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),],
                                        ttyEnabled: true, command: 'cat'),
                                containerTemplate(name: 'builder', image: 'hub.pingcap.net/tiflash/tiflash-builder',
                                        alwaysPullImage: true, ttyEnabled: true, command: 'cat',
                                        resourceRequestCpu: '12000m', resourceRequestMemory: '10Gi',
                                        resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
                        ]) {
                    node("build-tiflash-release") {
                        // if (ifFileCacheExists("tiflash",TIFLASH_HASH,"tiflash")){
                        //     return
                        // }
                        def ws = pwd()
                        // deleteDir()
                        container("builder") {
                            // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                            dir("tics") {
                                if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                    deleteDir()
                                }
                                def target = "tiflash"
                                def filepath = "builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${TIFLASH_HASH}/centos7/tiflash.tar.gz"


                                try{
                                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                                } catch (info) {
                                    retry(10) {
                                        echo "checkout failed, retry.."
                                        sleep 5
                                        if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                            deleteDir()
                                        }
                                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: ''], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                                    }
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
                                """
                                // build tiflash docker image
                                container("docker") {
                                    sh """
                                        cd release-centos7
                                        while ! make image_tiflash_ci ;do echo "fail @ `date "+%Y-%m-%d %H:%M:%S"`"; sleep 60; done
                                    """
                                    docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                                        sh """
                                            docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tiflash:${RELEASE_TAG}
                                            docker tag hub.pingcap.net/tiflash/tiflash-ci-centos7 hub.pingcap.net/tiflash/tics:${RELEASE_TAG}
                                            docker push hub.pingcap.net/tiflash/tiflash:${RELEASE_TAG}
                                            docker push hub.pingcap.net/tiflash/tics:${RELEASE_TAG}
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        
        builds["Build importer"] = {
            node("build") {
                // if (ifFileCacheExists("importer",IMPORTER_HASH,"importer")){
                //     return
                // }
                container("rust") {
                    // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    deleteDir()
                    dir("go/src/github.com/pingcap/importer") {
                
                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        def target = "importer"
                        def filepath = "builds/pingcap/importer/optimization/${RELEASE_TAG}/${IMPORTER_HASH}/centos7/importer.tar.gz"
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
        if (RELEASE_TAG >= "v5.2.0") {
            builds.remove("Build importer")
        }
        parallel builds
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
