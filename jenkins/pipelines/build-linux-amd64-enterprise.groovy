/*
* @RELEASE_TAG
*/
def slackcolor = 'good'
def githash
def os = "linux"
def arch = "amd64"
def TIDB_HASH
def TIKV_HASH
def PD_HASH
def TIFLASH_HASH

def label = "build-tiflash-release"

try {
    node("build_go1130") {
        container("golang") {
            def ws = pwd()
            deleteDir()

            stage("GO node") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                println "${ws}"
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                TIDB_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                TIKV_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tikv -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
                PD_HASH = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
            }

            stage("Build tidb") {
                dir("go/src/github.com/pingcap/tidb") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    def target = "tidb-server"
                    // def refspath = "refs/pingcap/tidb/${RELEASE_TAG}/sha1"
                    def filepath = "builds/pingcap/tidb/${TIDB_HASH}/centos7/tidb-server-${RELEASE_TAG}-enterprise.tar.gz"

                    // checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                    if(RELEASE_TAG == "master") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${TIDB_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tidb.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tidb.git']]]
                    }

                    sh """
                    mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                    make clean
                    go version
                    GOPATH=${ws}/go TIDB_EDITION=Enterprise make
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
                    checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: RELEASE_BRANCH]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', timeout: 2]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/enterprise-plugin.git']]]
                    def plugin_hash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                def filepath_whitelist = "builds/pingcap/tidb-plugins/optimization/enterprise/${RELEASE_TAG}/centos7/whitelist-1.so"
                def md5path_whitelist = "builds/pingcap/tidb-plugins/optimization/enterprise/${RELEASE_TAG}/centos7/whitelist-1.so.md5"
                def filepath_audit = "builds/pingcap/tidb-plugins/optimization/enterprise/${RELEASE_TAG}/centos7/audit-1.so"
                def md5path_audit = "builds/pingcap/tidb-plugins/optimization/enterprise/${RELEASE_TAG}/centos7/audit-1.so.md5"
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

            stage("Build pd") {
                dir("go/src/github.com/pingcap/pd") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    def target = "pd-server"
                    // def refspath = "refs/pingcap/pd/${RELEASE_TAG}/sha1"
                    def filepath = "builds/pingcap/pd/${PD_HASH}/centos7/pd-server-${RELEASE_TAG}-enterprise.tar.gz"

                    // checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PD_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/pd.git']]]
                    if(RELEASE_TAG == "master") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${PD_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/pd.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/pd.git']]]
                    }

                    sh """
                    go version
                    PD_EDITION=Enterprise make
                    PD_EDITION=Enterprise make tools
                    tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz *
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
    }

    node("build") {
        container("rust") {
            stage("Rust node") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            }

            stage("Build TiKV") {
                dir("go/src/github.com/pingcap/tikv") {
                    if (sh(returnStatus: true, script: '[ -d .git ] || git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }

                    def target = "tikv-server"
                    // def refspath = "refs/pingcap/tikv/${RELEASE_TAG}/sha1"
                    def filepath = "builds/pingcap/tikv/${TIKV_HASH}/centos7/tikv-server-${RELEASE_TAG}-enterprise.tar.gz"

                    // checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                    if(RELEASE_TAG == "master") {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIKV_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:tikv/tikv.git']]]
                    } else {
                        checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name:  "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'LocalBranch'],[$class: 'CloneOption', noTags: true]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:tikv/tikv.git']]]
                    }

                    sh """
                    grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                    if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                        echo using gcc 8
                        source /opt/rh/devtoolset-8/enable
                    fi
                    TIKV_EDITION=Enterprise CARGO_TARGET_DIR=.target ROCKSDB_SYS_STATIC=1 ROCKSDB_SYS_SSE=0 make dist_release
                    tar --exclude=${target}.tar.gz -czvf ${target}.tar.gz *
                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    """
                }
            }
        }
    }

    podTemplate(name: label, label: label,
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
                            alwaysPullImage: true, ttyEnabled: true, privileged: true, command: 'cat',
                            resourceRequestCpu: '12000m', resourceRequestMemory: '20Gi',
                            resourceLimitCpu: '16000m', resourceLimitMemory: '48Gi'),
            ]) {
        node(label) {
            container("builder") {
                stage("Build tiflash - prepare") {
                    deleteDir()
                    sh "rm -rf ./*"
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"

                    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
                    TIFLASH_HASH = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()

                }
                stage("Build tiflash - build") {
                    dir("tics") {
                        def target = "tiflash"
                        def filepath

                        filepath = "builds/pingcap/tiflash/${RELEASE_TAG}/${TIFLASH_HASH}/centos7/tiflash-${RELEASE_TAG}-enterprise.tar.gz"

                        retry(10) {
                            if(RELEASE_TAG == "master") {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${TIFLASH_HASH}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8], [$class: 'LocalBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'git@github.com:pingcap/tics.git']]]
                            } else {
                                checkout changelog: false, poll: true, scm: [$class: 'GitSCM', branches: [[name: "${RELEASE_TAG}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'CheckoutOption', timeout: 30], [$class: 'CloneOption', timeout: 60], [$class: 'LocalBranch'], [$class: 'CloneOption', noTags: true], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, trackingSubmodules: false, reference: '', shallow: true, threads: 8]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/tags/${RELEASE_TAG}:refs/tags/${RELEASE_TAG}", url: 'git@github.com:pingcap/tics.git']]]
                            }
                        }

                        sh """
                        TIFLASH_EDITION=Enterprise NPROC=12 release-centos7/build/build-release.sh
                        cd release-centos7/
                        tar -czvf tiflash.tar.gz tiflash
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