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
    GO_BIN_PATH="/usr/local/go1.16.4/bin"
} else {
    println "This build use go1.13"
}
println "GO_BIN_PATH=${GO_BIN_PATH}"


def slackcolor = 'good'
os = "linux"
arch = "arm64"
platform = "centos7"
def libs

def TIDB_CTL_HASH = "master"

try {
    stage("Validating HASH") {
        node("arm") {
            def ws = pwd()
            deleteDir()
            // println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            if (TIDB_HASH.length() < 40 || TIKV_HASH.length() < 40 || PD_HASH.length() < 40 || BINLOG_HASH.length() < 40 || TIFLASH_HASH.length() < 40 || TOOLS_HASH.length() < 40 || BR_HASH.length() < 40 || CDC_HASH.length() < 40) {
                println "build must be used with githash."
                sh "exit"
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
        build_para["tikv"] = TIKV_HASH
        build_para["tidb-binlog"] = BINLOG_HASH
        build_para["tidb-tools"] = TOOLS_HASH
        build_para["pd"] = PD_HASH
        build_para["ticdc"] = CDC_HASH
        build_para["br"] = BR_HASH
        build_para["dumpling"] = DUMPLING_HASH
        build_para["ng-monitoring"] = NGMonitoring_HASH
        build_para["enterprise-plugin"] = RELEASE_BRANCH
        build_para["tiflash"] = TIFLASH_HASH
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
                podTemplate(cloud: 'kubernetes-arm64', name: "build-arm-tiflash", label: "build-arm-tiflash",
                        instanceCap: 5, workspaceVolume: emptyDirWorkspaceVolume(memory: true),
                        containers: [containerTemplate(name: 'tiflash-build-arm', image: 'hub.pingcap.net/tiflash/tiflash-builder:arm64',
                                alwaysPullImage: true, ttyEnabled: true, privileged: true, command: 'cat',
                                resourceRequestCpu: '12000m', resourceRequestMemory: '20Gi',
                                resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
                                     containerTemplate(name: 'jnlp', image: 'hub.pingcap.net/jenkins/jnlp-slave-arm64:0.0.1'),
                        ]) {
                    node("build-arm-tiflash") {
                        container("tiflash-build-arm") {
                            // println "arm debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                            dir("tics") {
                                if (libs.check_file_exists(build_para, "tiflash")) {
                                    return
                                }

                                def target = "tiflash-${os}-${arch}"
                                def filepath = "builds/pingcap/tiflash/optimization/${RELEASE_TAG}/${TIFLASH_HASH}/${platform}/tiflash-${os}-${arch}.tar.gz"
                                
                                try {
                                    checkout changelog: false, poll: true,
                                            scm: [$class      : 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false,
                                                  extensions  : [[$class: 'CheckoutOption', timeout: 30],
                                                                 [$class: 'CloneOption', timeout: 60],
                                                                 [$class: 'PruneStaleBranch'],
                                                                 [$class: 'CleanBeforeCheckout'],
                                                                 [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8],
                                                                 [$class: 'LocalBranch']],
                                                  submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                                                         refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                                                         url          : 'git@github.com:pingcap/tics.git']]]

                                } catch (info) {
                                    retry(10) {
                                        echo "checkout failed, retry.."
                                        sleep 5
                                        if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                            deleteDir()
                                        }
                                        checkout changelog: false, poll: true,
                                                scm: [$class      : 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false,
                                                      extensions  : [[$class: 'CheckoutOption', timeout: 30],
                                                                     [$class: 'CloneOption', timeout: 60],
                                                                     [$class: 'PruneStaleBranch'],
                                                                     [$class: 'CleanBeforeCheckout'],
                                                                     [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8],
                                                                     [$class: 'LocalBranch']],
                                                      submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                                                             refspec      : '+refs/heads/*:refs/remotes/origin/*',
                                                                                             url          : 'git@github.com:pingcap/tics.git']]]
                                    }
                                }
                                sh """
                                    for a in \$(git tag --contains ${TIFLASH_HASH}); do echo \$a && git tag -d \$a;done
                                    git tag -f ${RELEASE_TAG} ${TIFLASH_HASH}
                                    git branch -D refs/tags/${RELEASE_TAG} || true
                                    git checkout -b refs/tags/${RELEASE_TAG}
                                """
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