properties([
        parameters([
                string(
                        defaultValue: '-1',
                        name: 'PIPELINE_BUILD_ID',
                        description: '',
                        trim: true
                )
     ])
])

begin_time = new Date().format('yyyy-MM-dd HH:mm:ss')
githash = ""
def BUILD_URL = 'git@github.com:pingcap/tiflow.git'
def build_path = 'go/src/github.com/pingcap/dm'
def UCLOUD_OSS_URL = "http://pingcap-dev.hk.ufileos.com"
def branch = (env.TAG_NAME==null) ? "${env.BRANCH_NAME}" : "refs/tags/${env.TAG_NAME}"
def slackcolor = 'good'
def ws

def upload_result_to_db() {
    pipeline_build_id = params.PIPELINE_BUILD_ID
    pipeline_id = "6"
    pipeline_name = "DM"
    status = currentBuild.result
    build_number = BUILD_NUMBER
    job_name = JOB_NAME
    artifact_meta = "dm commit:" + githash
    begin_time = begin_time
    end_time = new Date().format('yyyy-MM-dd HH:mm:ss')
    triggered_by = "sre-bot"
    component = "dm"
    arch = "linux-amd64"
    artifact_type = "binary"
    branch = "master"
    version = "None"
    build_type = "dev-build"
    push_gcr = "No"

    build job: 'upload_result_to_db',
            wait: true,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_BUILD_ID', value: pipeline_build_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_ID', value: pipeline_id],
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: pipeline_name],
                    [$class: 'StringParameterValue', name: 'STATUS', value: status],
                    [$class: 'StringParameterValue', name: 'BUILD_NUMBER', value: build_number],
                    [$class: 'StringParameterValue', name: 'JOB_NAME', value: job_name],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_META', value: artifact_meta],
                    [$class: 'StringParameterValue', name: 'BEGIN_TIME', value: begin_time],
                    [$class: 'StringParameterValue', name: 'END_TIME', value: end_time],
                    [$class: 'StringParameterValue', name: 'TRIGGERED_BY', value: triggered_by],
                    [$class: 'StringParameterValue', name: 'COMPONENT', value: component],
                    [$class: 'StringParameterValue', name: 'ARCH', value: arch],
                    [$class: 'StringParameterValue', name: 'ARTIFACT_TYPE', value: artifact_type],
                    [$class: 'StringParameterValue', name: 'BRANCH', value: branch],
                    [$class: 'StringParameterValue', name: 'VERSION', value: version],
                    [$class: 'StringParameterValue', name: 'BUILD_TYPE', value: build_type],
                    [$class: 'StringParameterValue', name: 'PUSH_GCR', value: push_gcr]
            ]

}

// choose which go version to use. 
def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        if (branchNameOrTag >= "v6.3") {
            println "tag ${branchNameOrTag} use go 1.19"
            return "go1.19"
        }
        if (branchNameOrTag >= "v6.0") {
            println "tag ${branchNameOrTag} use go 1.18"
            return "go1.18"
        }
        if (branchNameOrTag >= "v5.1") {
            println "tag ${branchNameOrTag} use go 1.16"
            return "go1.16"
        }
        if (branchNameOrTag < "v5.1") {
            println "tag ${branchNameOrTag} use go 1.13"
            return "go1.13"
        }
        println "tag ${branchNameOrTag} use default version go 1.19"
        return "go1.19"
    } else { 
        println "this is a branch"
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.19")
            return "go1.19"
        }


        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-6.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.19")
            return "go1.19"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.1"  && branchNameOrTag >= "release-6.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.0" && branchNameOrTag >= "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.16")
            return "go1.16"
        }

        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.13")
            return "go1.13"
        }
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.18"
        return "go1.19"
    }
}


def GO_BUILD_SLAVE = "build_go1190"
def goVersion = selectGoVersion(env.BRANCH_NAME)
if ( goVersion == "go1.18" ) {
    GO_BUILD_SLAVE = "build_go1180"
}
if ( goVersion == "go1.16" ) {
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
}
if ( goVersion == "go1.13" ) {
    GO_BUILD_SLAVE = "build_go1130_memvolume"
}

println "This build use ${goVersion}"

env.DOCKER_HOST = "tcp://localhost:2375"

try {
    def buildSlave = "${GO_BUILD_SLAVE}"

    node(buildSlave) {
        stage("X86 - Prepare") {
            ws = pwd()
            deleteDir()
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
        }

        stage("X86 - Checkout") {
            dir(build_path) {
                println branch
                retry(3) {
                    if(branch.startsWith("refs/tags")) {
                        checkout changelog: false,
                                poll: true,
                                scm: [$class: 'GitSCM',
                                        branches: [[name: branch]],
                                        doGenerateSubmoduleConfigurations: false,
                                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                    [$class: 'LocalBranch'],
                                                    [$class: 'CloneOption', noTags: true, timeout: 60]],
                                        submoduleCfg: [],
                                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                            refspec: "+${branch}:${branch}",
                                                            url: "${BUILD_URL}"]]
                                ]
                    } else {
                        checkout scm: [$class: 'GitSCM', 
                            branches: [[name: branch]],  
                            extensions: [
                                    [$class             : 'SubmoduleOption',
                                    disableSubmodules  : false,
                                    parentCredentials  : true,
                                    recursiveSubmodules: true,
                                    trackingSubmodules : false,
                                    reference          : ''],
                                    [$class: 'PruneStaleBranch'],
                                    [$class: 'CleanBeforeCheckout'],
                            ],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: "+refs/heads/*:refs/remotes/origin/*", url: "${BUILD_URL}"]]]
                    }
                }

                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("X86 - Build binary") {
            dir(build_path) {
                container("golang") {
                    timeout(30) {
                        if ((branch.startsWith("release-") && branch <"release-6.0") || (branch.startsWith("v") && branch <"v6.0.0")) {
                            sh """
                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            GOPATH=\$GOPATH:${ws}/go make dm
                            """
                        }else {
                            cwd = pwd()
                            sh """
                            wget http://fileserver.pingcap.net/download/ee-tools/node-v16.14.0-linux-x64.tar.gz
                            tar -xvf node-v16.14.0-linux-x64.tar.gz
                            export PATH=${cwd}/node-v16.14.0-linux-x64/bin:\$PATH
                            node -v
                            npm install -g yarn
                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            GOPATH=\$GOPATH:${ws}/go make dm-master-with-webui dm-worker dmctl dm-syncer
                            """
                        }
                    }
                }
            }
        }

        stage("X86 - Upload dm binary") {
            dir(build_path) {
                def target = "dm-linux-amd64"
                def refspath = "refs/pingcap/dm/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/dm/${githash}/centos7/${target}.tar.gz"
                container("golang") {
                    timeout(10) {
                        sh """
                        echo "${githash}" > sha1
                        curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        mkdir ${target}
                        mkdir ${target}/bin
                        mkdir ${target}/conf
                        mv bin/dm* ${target}/bin/
                        """
                        
                        // DM changed the folder after a PR later than v6.2.0. Hotfix branch will be named like release-6.2-yyyymmdd and we should keep old logic for it.
                        if ((branch.startsWith("release-") && branch < "release-6.3") || (branch.startsWith("v") && branch < "v6.3")) {
                            sh """
                            mv dm/dm/master/task_basic.yaml ${target}/conf/
                            mv dm/dm/master/task_advanced.yaml ${target}/conf/
                            mv dm/dm/master/dm-master.toml ${target}/conf/
                            mv dm/dm/worker/dm-worker.toml ${target}/conf/
                            """
                        } else {
                            sh """
                            mv dm/master/task_basic.yaml ${target}/conf/
                            mv dm/master/task_advanced.yaml ${target}/conf/
                            mv dm/master/dm-master.toml ${target}/conf/
                            mv dm/worker/dm-worker.toml ${target}/conf/
                            """
                        }
                        sh """
                        mv LICENSE ${target}/
                        curl http://download.pingcap.org/mydumper-latest-linux-amd64.tar.gz | tar xz
                        mv mydumper-latest-linux-amd64/bin/mydumper ${target}/bin/ && rm -rf mydumper-latest-linux-amd64
                        tar -czvf ${target}.tar.gz ${target}

                        # setup upload tools
                        # curl -O ${FILE_SERVER_URL}/download/script/filemgr-linux64
                        # curl -O ${FILE_SERVER_URL}/download/script/config.cfg
                        # chmod +x filemgr-linux64

                        curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                        # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/dm/${githash}/centos7/${target}.tar.gz --file ${target}.tar.gz
                        """
                        // writeFile file: 'sha1', text: "${githash}"
                        // sh "./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key refs/pingcap/dm/${env.BRANCH_NAME}/sha1 --file sha1"

                        // cleanup
                        sh "rm -rf sha1 ${target}.tar.gz"
                    }
                }
            }
        }
        // do not release dm-ansible  after v6.0.0
        if ((branch.startsWith("release-") && branch <"release-6.0") || (branch.startsWith("v") && branch <"v6.0.0")) {
            stage("package dm-ansible") {
                dir(build_path) {
                    container("golang") {
                        timeout(10) {
                            sh """
                            cp -r dm/dm/dm-ansible ./
                            tar -czvf dm-ansible.tar.gz dm-ansible
                            """
                        }
                    }
                }
            }

            stage("Upload dm-ansible") {
                dir(build_path) {
                    container("golang") {
                        def refspath = "refs/pingcap/dm/${env.BRANCH_NAME}/dm-ansible-sha1"
                        def filepath = "builds/pingcap/dm/${githash}/centos7/dm-ansible.tar.gz"

                        writeFile file: 'dm-ansible-sha1', text: "${githash}"
                        sh """
                        # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key ${refspath} --file dm-ansible-sha1
                        curl -F ${refspath}=@dm-ansible-sha1 ${FILE_SERVER_URL}/upload 

                        # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/dm/${githash}/centos7/dm-ansible.tar.gz --file dm-ansible.tar.gz
                        curl -F ${filepath}=@dm-ansible.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }

            stage("Generate monitoring") {
                dir(build_path) {
                    container("golang") {
                        timeout(30) {
                            sh """
                            cd dm
                            mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                            cp -f dm/dm-ansible/scripts/DM-Monitor-Professional.json monitoring/dashboards/
                            cp -f dm/dm-ansible/scripts/DM-Monitor-Standard.json monitoring/dashboards/
                            cp -f dm/dm-ansible/scripts/dm_instances.json monitoring/dashboards/
                            mkdir -p monitoring/rules
                            cp -f dm/dm-ansible/conf/dm_worker.rules.yml monitoring/rules/
                            GOPATH=\$GOPATH:${ws}/go cd monitoring && go run dashboards/dashboard.go
                            """
                        }
                    }
                }
                stash includes: "go/src/github.com/pingcap/dm/dm/monitoring/**", name: "monitoring"
            }
        } else {
            stage("package dm-ansible") {
                dir(build_path) {
                    container("golang") {
                        timeout(10) {
                            sh """
                            mkdir -p dm-ansible
                            mkdir -p dm-ansible/conf
                            mkdir -p dm-ansible/scripts
                            cp dm/metrics/alertmanager/dm_worker.rules.yml dm-ansible/conf
                            cp dm/metrics/grafana/* dm-ansible/scripts
                            tar -czvf dm-ansible.tar.gz dm-ansible
                            """
                        }
                    }
                }
            }

            stage("Upload dm-ansible") {
                dir(build_path) {
                    container("golang") {
                        def refspath = "refs/pingcap/dm/${env.BRANCH_NAME}/dm-ansible-sha1"
                        def filepath = "builds/pingcap/dm/${githash}/centos7/dm-ansible.tar.gz"

                        writeFile file: 'dm-ansible-sha1', text: "${githash}"
                        sh """
                        # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key ${refspath} --file dm-ansible-sha1
                        curl -F ${refspath}=@dm-ansible-sha1 ${FILE_SERVER_URL}/upload 

                        # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/dm/${githash}/centos7/dm-ansible.tar.gz --file dm-ansible.tar.gz
                        curl -F ${filepath}=@dm-ansible.tar.gz ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }
    
    node("arm") {
        stage("ARM - Prepare") {
            ws = pwd()
            deleteDir()
            println "arm node: \n${NODE_NAME}"
        }

        stage("ARM - Checkout") {
            dir(build_path) {
                println branch
                retry(3) {
                    if(branch.startsWith("refs/tags")) {
                        checkout changelog: false,
                                poll: true,
                                scm: [$class: 'GitSCM',
                                        branches: [[name: branch]],
                                        doGenerateSubmoduleConfigurations: false,
                                        extensions: [[$class: 'CheckoutOption', timeout: 30],
                                                    [$class: 'LocalBranch'],
                                                    [$class: 'CloneOption', noTags: true, timeout: 60]],
                                        submoduleCfg: [],
                                        userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh',
                                                            refspec: "+${branch}:${branch}",
                                                            url: "${BUILD_URL}"]]
                                ]
                    } else {
                        checkout scm: [$class: 'GitSCM', 
                            branches: [[name: branch]],  
                            extensions: [[$class: 'LocalBranch']],
                            userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}"]]]
                    }
                }

                githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
            }
        }

        stage("ARM - Build binary") {
            dir(build_path) {
                timeout(30) {
                    if ((branch.startsWith("release-") && branch <"release-6.0") || (branch.startsWith("v") && branch <"v6.0.0")) {
                        sh """
                        export PATH=${GO_BIN_PATH}:/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin
                        go version
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=\$GOPATH:${ws}/go make dm
                        """
                    }else {
                        cwd = pwd()
                        sh """
                        export PATH=${GO_BIN_PATH}:/usr/local/node/bin:/root/go/bin:/root/.cargo/bin:/usr/lib64/ccache:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/root/bin
                        wget http://fileserver.pingcap.net/download/ee-tools/node-v16.14.0-linux-arm64.tar.gz
                        tar -xvf node-v16.14.0-linux-arm64.tar.gz
                        export PATH=${cwd}/node-v16.14.0-linux-arm64/bin:\$PATH
                        node -v
                        npm install -g yarn
                        go version
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=\$GOPATH:${ws}/go make dm-master-with-webui dm-worker dmctl dm-syncer
                        """
                    }
                }
            }
        }

        stage("ARM - Upload dm binary") {
            dir(build_path) {
                def target = "dm-linux-arm64"
                def refspath = "refs/pingcap/dm/${env.BRANCH_NAME}/sha1"
                def filepath = "builds/pingcap/dm/${githash}/centos7/${target}.tar.gz"
                timeout(10) {
                    sh """
                    echo "${githash}" > sha1
                    curl -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                    mkdir ${target}
                    mkdir ${target}/bin
                    mkdir ${target}/conf
                    mv bin/dm* ${target}/bin/
                    """

                    // DM changed the folder after a PR later than v6.2.0. Hotfix branch will be named like release-6.2-yyyymmdd and we should keep old logic for it.
                    if ((branch.startsWith("release-") && branch < "release-6.3") || (branch.startsWith("v") && branch < "v6.3")) {
                        sh """
                        mv dm/dm/master/task_basic.yaml ${target}/conf/
                        mv dm/dm/master/task_advanced.yaml ${target}/conf/
                        mv dm/dm/master/dm-master.toml ${target}/conf/
                        mv dm/dm/worker/dm-worker.toml ${target}/conf/
                        """
                    } else {
                        sh """
                        mv dm/master/task_basic.yaml ${target}/conf/
                        mv dm/master/task_advanced.yaml ${target}/conf/
                        mv dm/master/dm-master.toml ${target}/conf/
                        mv dm/worker/dm-worker.toml ${target}/conf/
                        """
                    }

                    sh """
                    mv LICENSE ${target}/
                    # curl http://download.pingcap.org/mydumper-latest-linux-amd64.tar.gz | tar xz
                    # mv mydumper-latest-linux-amd64/bin/mydumper ${target}/bin/ && rm -rf mydumper-latest-linux-amd64
                    tar -czvf ${target}.tar.gz ${target}

                    # setup upload tools
                    # curl -O ${FILE_SERVER_URL}/download/script/filemgr-linux64
                    # curl -O ${FILE_SERVER_URL}/download/script/config.cfg
                    # chmod +x filemgr-linux64

                    curl -F ${filepath}=@${target}.tar.gz ${FILE_SERVER_URL}/upload
                    # ./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key builds/pingcap/dm/${githash}/centos7/${target}.tar.gz --file ${target}.tar.gz
                    """
                    // writeFile file: 'sha1', text: "${githash}"
                    // sh "./filemgr-linux64 --action mput --bucket pingcap-dev --nobar --key refs/pingcap/dm/${env.BRANCH_NAME}/sha1 --file sha1"

                    // cleanup
                    sh "rm -rf sha1 ${target}.tar.gz"
                }
            }
        }
    }

    // we Build and Push monitoring image here, because no source code in `release_dm` job.
    if (branch != "release-1.0" && !branch.startsWith("v1.")) {
        RELEASE_TAG = branch
        if (env.TAG_NAME != null) {
            RELEASE_TAG = "${env.TAG_NAME}"
        } else if (RELEASE_TAG == "master") {
            RELEASE_TAG = "nightly"
        } else {
            RELEASE_TAG = "${RELEASE_TAG}-nightly"
        }
        // do not release dm-monitor-initializer after v6.0.0
        if ((branch.startsWith("release-") && branch <"release-6.0") || (branch.startsWith("v") && branch <"v6.0.0")) {
            stage("Publish Monitor Docker Image") {
                node("delivery") {
                    container("delivery") {
                        deleteDir()
                        unstash 'monitoring'
                        dir("go/src/github.com/pingcap/dm/dm/monitoring") {
                            withDockerServer([uri: "${env.DOCKER_HOST}"]) {
                                docker.build("pingcap/dm-monitor-initializer:${RELEASE_TAG}").push()
                            }
                            docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
                                sh """
                                    docker tag pingcap/dm-monitor-initializer:${RELEASE_TAG} uhub.service.ucloud.cn/pingcap/dm-monitor-initializer:${RELEASE_TAG}
                                    docker push uhub.service.ucloud.cn/pingcap/dm-monitor-initializer:${RELEASE_TAG}
                                """
                            }
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
}finally{
    if(env.BRANCH_NAME == 'master'){
         upload_result_to_db()
    }
}
stage('Summary') {
    echo "Send slack here ..."
    //slackSend channel: "", color: "${slackcolor}", teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
    def slackmsg_succ = "[${env.JOB_NAME.replaceAll('%2F','/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
            "Elapsed Time: `${duration}` Mins" + "\n" +
            "Build Branch: `${env.BRANCH_NAME}`, Githash: `${githash.take(7)}`" + "\n" +
            "Binary Download URL:" + "\n" +
            "${UCLOUD_OSS_URL}/builds/pingcap/dm/${githash}/centos7/dm-linux-amd64.tar.gz"
    def slackmsg_fail = "${currentBuild.result}: `${env.JOB_NAME}` #${env.BUILD_NUMBER}:\n${env.RUN_DISPLAY_URL}\n @here"

    if (currentBuild.result != "SUCCESS") {
        slackSend channel: '#iamgroot', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg_fail}"
    } else {
        slackSend channel: '#iamgroot', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg_succ}"
    }
}