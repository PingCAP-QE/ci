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
* @TIKV_PRID
*/
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


def slackcolor = 'good'
def githash
os = "linux"
arch = "amd64"
platform = "centos7"

def ifFileCacheExists(product,hash,binary) {
    if (params.FORCE_REBUILD){
        return false
    }
    if(!fileExists("gethash.py")){
        sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    }
    def filepath = "builds/pingcap/${product}/optimization/${hash}/${platform}/${binary}.tar.gz"
    if (product == "br") {
        filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/${platform}/${binary}.tar.gz"
    }
    if (product == "ticdc") {
        filepath = "builds/pingcap/${product}/optimization/${hash}/${platform}/${product}-${os}-${arch}.tar.gz"
    } 
    if (product == "tiflash") {
        filepath = "builds/pingcap/${product}/optimization/${RELEASE_TAG}/${hash}/${platform}/${binary}.tar.gz"
    }  

    result = sh(script: "curl -I ${FILE_SERVER_URL}/download/${filepath} -X \"HEAD\"|grep \"200 OK\"", returnStatus: true)
    // result equal 0 mean cache file exists
    if (result==0) {
        echo "file ${FILE_SERVER_URL}/download/${filepath} found in cache server,skip build again"
        return true
    }
    return false
}

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
                    if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || IMPORTER_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                        println "build must be used with githash."
                        sh "exit 2"
                    }
                }
            }
        }
    }

    stage("Build") {
        builds = [:]


        builds["Build tidb-ctl"] = {
            node("${GO_BUILD_SLAVE}") {
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build tidb && plugins"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("tidb",TIDB_HASH,"tidb-server")){
                    return
                }
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
                        def filepath = "builds/pingcap/tidb/optimization/${TIDB_HASH}/centos7/tidb-server.tar.gz"
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
        builds["Build tidb-binlog"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("tidb-binlog",BINLOG_HASH,"tidb-binlog")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build tidb-tools"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("tidb-tools",TOOLS_HASH,"tidb-tools")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build pd"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("pd",PD_HASH,"pd-server")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build cdc"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("ticdc",CDC_HASH,"ticdc")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build dumpling"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("dumpling",DUMPLING_HASH,"dumpling")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
            }
        }
        builds["Build br"] = {
            node("${GO_BUILD_SLAVE}") {
                if (ifFileCacheExists("br",BR_HASH,"br")){
                    return
                }
                container("golang") {
                    def ws = pwd()
                    deleteDir()
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
                        if (ifFileCacheExists("tiflash",TIFLASH_HASH,"tiflash")){
                            return
                        }
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
                                def filepath2 = "builds/pingcap/tiflash/optimization/${TIFLASH_HASH}/centos7/tiflash.tar.gz"

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
                                    curl -F ${filepath2}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
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

        builds["Build tikv"] = {
            node("build") {
                if (ifFileCacheExists("tikv",TIKV_HASH,"tikv-server")){
                    return
                }
                container("rust") {
                    // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    deleteDir()
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

                            def specStr = "+refs/pull/*:refs/remotes/origin/pr/*"
                            if (TIKV_PRID != null && TIKV_PRID != "") {
                                specStr = "+refs/pull/${TIKV_PRID}/*:refs/remotes/origin/pr/${TIKV_PRID}/*"
                            }
                            def branch = TIKV_HASH
                            if (RELEASE_BRANCH != null && RELEASE_BRANCH != "") {
                                branch =RELEASE_BRANCH
                            }

                            checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: specStr, url: 'git@github.com:tikv/tikv.git']]]
                            sh """
                                git checkout -f ${TIKV_HASH}
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
            }
        }
        builds["Build importer"] = {
            node("build") {
                if (ifFileCacheExists("importer",IMPORTER_HASH,"importer")){
                    return
                }
                container("rust") {
                    // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    deleteDir()
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