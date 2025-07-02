/*
* @TIDB_HASH
* @TIKV_HASH
* @PD_HASH
* @BINLOG_HASH
* @CDC_HASH
* @DM_HASH
* @BR_HASH
* @DUMPLING_HASH
* @TIFLASH_HASH
* @RELEASE_TAG
* @RELEASE_BRANCH
* @ARCH_ARM
* @ARCH_X86
* @ARCH_MAC
* @ARCH_MAC_ARM
* @TIUP_ENV
* @DEBUG_MODE
*/

// tiup-ctl 一般不会变更，可以固定使用 v1.8.1 版本
final TIUP_VERSION = 'v1.8.1'
final ETCDCTL_VERSION = 'v3.4.30'

def get_hash = { hash_or_branch, repo ->
    if (DEBUG_MODE == "true") {
        return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${RELEASE_BRANCH} -source=github").trim()
    } else {
        if (hash_or_branch.length() == 40) {
            return hash_or_branch
        } else {
            return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${RELEASE_TAG} -s=${FILE_SERVER_URL}").trim()
        }
    }
}

def tidb_sha1, tikv_sha1, pd_sha1, tidb_ctl_sha1, dm_sha1, tidb_binlog_sha1, br_sha1, ticdc_sha1, lightning_sha1, dumpling_sha1, tiflash_sha1, platform, tag, tarball_name, tidb_version
def tidb_desc = "TiDB is an open source distributed HTAP database compatible with the MySQL protocol"
def tikv_desc = "Distributed transactional key-value database, originally created to complement TiDB"
def pd_desc = "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster"
def ctl_desc = "TiDB controller suite"
def pump_desc = "The pump componet of TiDB binlog service"
def drainer_desc = "The drainer componet of TiDB binlog service"
def pd_recover_desc = "PD Recover is a disaster recovery tool of PD, used to recover the PD cluster which cannot start or provide services normally"

def dm_master_desc = "dm-master component of Data Migration Platform"
def dm_worker_desc = "dm-worker component of Data Migration Platform"
def dmctl_desc = "dmctl component of Data Migration Platform"

taskStartTimeInMillis = System.currentTimeMillis()
taskFinishTimeInMillis = System.currentTimeMillis()

def download = { name, hash, os, arch ->
    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    } else {
        sh """
        exit 1
        """
    }

    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${name}/optimization/${tag}/${hash}/${platform}/${tarball_name}
    """
}

def unpack = { name, os, arch ->
    tarball_name = "${name}-${os}-${arch}.tar.gz"

    sh """
    tar -zxf ${tarball_name}
    """
}

def pack = { name, version, os, arch ->
    sh """
    mkdir -p ctls
    mkdir -p package
    """

    if (name == "tidb") {
        sh """
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server
        tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tidb_desc}"
        """
    } else if (name == "tikv") {
        sh """
        mv bin/${name}-ctl ctls/
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server
        tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${tikv_desc}"
        """
    } else if (name == "pd") {
        sh """
        mv bin/${name}-ctl ctls/
        tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server
        tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name}-server --arch ${arch} --os ${os} --desc="${pd_desc}"
        tar -C bin -czvf package/${name}-recover-${version}-${os}-${arch}.tar.gz ${name}-recover
        tiup mirror publish ${name}-recover ${tidb_version} package/${name}-recover-${version}-${os}-${arch}.tar.gz ${name}-recover --arch ${arch} --os ${os} --desc="${pd_recover_desc}"
        """
    } else if (name == 'tidb-binlog') {
        sh """
        tar -C bin -czvf package/pump-${version}-${os}-${arch}.tar.gz pump
        tiup mirror publish pump ${tidb_version} package/pump-${version}-${os}-${arch}.tar.gz pump --arch ${arch} --os ${os} --desc="${pump_desc}"
        tar -C bin -czvf package/drainer-${version}-${os}-${arch}.tar.gz drainer
        tiup mirror publish drainer ${tidb_version} package/drainer-${version}-${os}-${arch}.tar.gz drainer --arch ${arch} --os ${os} --desc="${drainer_desc}"
        mv bin/binlogctl ctls/
        """
    } else if (name == "tidb-ctl") {
        sh "mv bin/${name} ctls/"
    } else if (name == "dm") {
        sh "[ -d package ] || mkdir package"
        // release version <= v6.0.0 exists dir dm-ansible and monitoring
        if (RELEASE_TAG != "nightly" && RELEASE_TAG >= "v5.3.0" && RELEASE_TAG <= "v6.0.0") {
            sh """
            if [[ -d "dm-ansible" ]]; then
                echo "dm-ansible dir exists"
            else
                echo "dm-ansible dir not exists, is something wrong? (dm version >= 5.3.0 and < 6.0.0 need dm-ansible dir)"
                exit 1
            fi;
            if [[ -d "monitoring" ]]; then
                echo "monitoring dir exists"
            else
                echo "monitoring dir not exists, is something wrong? dm version >= 5.3.0 and < 6.0.0 need monitoring dir"
                exit 1
            fi;
            """
        } else {
            sh """
            if [[ -d "monitoring" ]]; then
                echo "monitoring dir exists, is something wrong? dm version >= 6.0.0 not exist monitoring dir"
                exit 1
            else
                echo "monitoring dir not exists, it is expected"
            fi;
            # pr about dm-ansible why exist on version >= 6.0.0
            #  1. tiflow delete dm-ansible dir https://github.com/pingcap/tiflow/pull/4917
            #  2. build-commmon suppport config file bewteen versin >=6.0.0 and version < 6.0.0
            #     https://github.com/PingCAP-QE/jenkins-templates/pull/225
            if [[ -d "dm-ansible" ]]; then
                echo "dm-ansible dir exists even though dm version >= 6.0.0"
                echo "some backround info https://github.com/PingCAP-QE/jenkins-templates/pull/225"
            else
                echo "dm-ansible dir not exists, is something wrong? (dm version >=6.0.0 need dm-ansible origin from dm/metrics)"
                exit 1
            fi;
            """
        }
        // TODO: dm-ansible has been remove from the repo since v6.0.0.
        sh """
        echo "package dm-master"
        mkdir ${name}-master
        mkdir ${name}-master/conf
        mkdir ${name}-master/scripts
        cp bin/dm-master ${name}-master
        if [[ -d "dm-ansible" ]]; then
            cp -r dm-ansible/conf/* ${name}-master/conf
            cp dm-ansible/scripts/* ${name}-master/scripts
        fi;
        tar -czvf package/${name}-master-${version}-${os}-${arch}.tar.gz ${name}-master
        rm -rf ${name}-master

        echo "package dm-worker"
        mkdir ${name}-worker
        mkdir ${name}-worker/conf
        mkdir ${name}-worker/scripts
        cp bin/dm-worker ${name}-worker
        if [[ -d "dm-ansible" ]]; then
            cp -r dm-ansible/conf/* ${name}-worker/conf
            cp dm-ansible/scripts/* ${name}-worker/scripts
        fi;
        tar -czvf package/${name}-worker-${version}-${os}-${arch}.tar.gz ${name}-worker
        rm -rf ${name}-worker

        echo "package dmctl"
        mkdir ${name}ctl
        mkdir ${name}ctl/conf
        mkdir ${name}ctl/scripts
        cp bin/dmctl ${name}ctl
        if [[ -d "dm-ansible" ]]; then
            cp -r dm-ansible/conf/* ${name}ctl/conf
            cp dm-ansible/scripts/* ${name}ctl/scripts
        fi;
        tar -czvf package/${name}ctl-${version}-${os}-${arch}.tar.gz ${name}ctl
        rm -rf ${name}ctl
        """

        sh """
        tiup mirror publish ${name}-master ${tidb_version} package/${name}-master-${version}-${os}-${arch}.tar.gz ${name}-master/${name}-master --arch ${arch} --os ${os} --desc="${dm_master_desc}"
        tiup mirror publish ${name}-worker ${tidb_version} package/${name}-worker-${version}-${os}-${arch}.tar.gz ${name}-worker/${name}-worker --arch ${arch} --os ${os} --desc="${dm_worker_desc}"
        tiup mirror publish ${name}ctl ${tidb_version} package/${name}ctl-${version}-${os}-${arch}.tar.gz ${name}ctl/${name}ctl --arch ${arch} --os ${os} --desc="${dmctl_desc}"
        """
    } else {
        sh """
        if(TIUP_ENV=="prod"){
            tar -C bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}
        }else{
            tar -C ${name}-${version}-${os}-${arch}/bin -czvf package/${name}-${version}-${os}-${arch}.tar.gz ${name}
        }
        tiup mirror publish ${name} ${tidb_version} package/${name}-${version}-${os}-${arch}.tar.gz ${name} --arch ${arch} --os ${os} --desc=""
        """
    }

    sh """
    rm -rf ${name}*.tar.gz
    """
}

def update = { name, version, hash, os, arch ->
    try {
        download name, hash, os, arch
        unpack name, os, arch
        pack name, version, os, arch
    } catch (e) {
        echo "update ${name}-${version}-${os}-${arch}: ${e}"
        throw e
    }
}

def update_ctl = { version, os, arch ->

    if (os == "linux") {
        platform = "centos7"
    } else if (os == "darwin" && arch == "amd64") {
        platform = "darwin"
    } else if (os == "darwin" && arch == "arm64") {
        platform = "darwin-arm64"
    } else {
        sh """
        exit 1
        """
    }

    lightning_tarball_name = "br-${os}-${arch}.tar.gz"
    lightning_ctl_bin_dir = "bin/tidb-lightning-ctl"


    if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
        // release and pre-release version
        if (RELEASE_TAG != "nightly") {
            // download cdc and lightning cached tar.gz to get ctl binary
            sh """
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/optimization/${RELEASE_TAG}/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${RELEASE_TAG}/${lightning_sha1}/${platform}/${lightning_tarball_name}
            """
            // nightly version
        } else {
            sh """
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/ticdc/master/${ticdc_sha1}/${platform}/ticdc-${os}-${arch}.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/master/${lightning_sha1}/${platform}/${lightning_tarball_name}
            """
        }
        sh """
        tar -zxf ${lightning_tarball_name}
        cp ${lightning_ctl_bin_dir} ctls/
        tar xf ticdc-${os}-${arch}.tar.gz
        cp bin/cdc ctls/
        """
    }

    sh """
    mkdir -p tiup/components/ctl
    curl -L http://fileserver.pingcap.net/download/tiup/releases/${TIUP_VERSION}/tiup-${TIUP_VERSION}-${os}-${arch}.tar.gz | tar -C tiup/components/ctl -xz bin/tiup-ctl
    mv tiup/components/ctl/bin/tiup-ctl ctls/ctl
    curl -L ${FILE_SERVER_URL}/download/pingcap/etcd-${ETCDCTL_VERSION}-${os}-${arch}.tar.gz | tar xz
    mv etcd-v3.4.30-${os}-${arch}/etcdctl ctls/
    tar -C ctls -czvf package/ctl-${version}-${os}-${arch}.tar.gz \$(ls ctls)
    tiup mirror publish ctl ${tidb_version} package/ctl-${version}-${os}-${arch}.tar.gz ctl --arch ${arch} --os ${os} --desc="${ctl_desc}"
    rm -rf ctls
    """
}

def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def cloud = "kubernetes"
    def namespace = "jenkins-cd"
    def pod_builder_image = 'hub.pingcap.net/jenkins/tiup'
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'tiup', alwaysPullImage: true,
                            image: "${pod_builder_image}", ttyEnabled: true,
                            resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],

                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
            ],
    ) {
        node(label){
            container("tiup"){
                println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
                withCredentials([file(credentialsId: 'tiup-prod-key', variable: 'TIUPKEY_JSON')]) {
                    sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                    body()
                }
            }
        }
    }
}


try {
node("build_go1130") {
    container("golang") {
        timeout(360) {
            stage("Prepare") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                deleteDir()
            }

            stage("Get component hash") {
                sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

                if (RELEASE_TAG == "nightly") {
                    tag = "v8.5.0-alpha"
                    RELEASE_TAG = "v8.5.0-alpha"
                } else {
                    tag = RELEASE_TAG
                }

                tidb_sha1 = get_hash(TIDB_HASH, "tidb")
                tidb_ctl_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-ctl/master/sha1").trim()
                tikv_sha1 = get_hash(TIKV_HASH, "tikv")
                pd_sha1 = get_hash(PD_HASH, "pd")
                tiflash_sha1 = get_hash(TIFLASH_HASH, "tics")
                tidb_binlog_sha1 = get_hash(BINLOG_HASH, "tidb-binlog")
                dumpling_sha1 = ""
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                    dumpling_sha1 = tidb_sha1
                } else {
                    dumpling_sha1 = get_hash(DUMPLING_HASH, "dumpling")
                }
                br_sha1 = ""
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.2.0") {
                    br_sha1 = tidb_sha1
                } else {
                    br_sha1 = get_hash(BR_HASH, "br")
                }
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v4.0.0") {
                    ticdc_sha1 = get_hash(CDC_HASH, "tiflow")
                }
                lightning_sha1 = ""
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.2.0") {
                    lightning_sha1 = get_hash(BR_HASH, "tidb")
                } else {
                    lightning_sha1 = get_hash(BR_HASH, "br")
                }
                if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                    dm_sha1 = get_hash(DM_HASH, "tiflow")
                }


                println "tidb_sha1: ${tidb_sha1}"
                println "br_sha1: ${br_sha1}"
                println "dumpling_sha1: ${dumpling_sha1}"
                println "tidb_ctl_sha1: ${tidb_ctl_sha1}"
                println "tikv_sha1: ${tikv_sha1}"
                println "pd_sha1: ${pd_sha1}"
                println "tidb_binlog_sha1: ${tidb_binlog_sha1}"
                println "ticdc_sha1: ${ticdc_sha1}"
                println "lightning_sha1: ${lightning_sha1}"
                println "dm_sha1: ${dm_sha1}"
                println "tiflash_sha1: ${tiflash_sha1}"
            }

            if (RELEASE_TAG == "v8.5.0-alpha") {
                stage("Get version info when nightly") {
                    dir("tidb") {
                        // sh"""
                        // wget ${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz
                        // tar xf tidb-server.tar.gz
                        // """
                        // tidb_version = sh(returnStdout: true, script: "./bin/tidb-server -V | awk 'NR==1{print \$NF}' | sed -r 's/(^[^-]*).*/\\1/'").trim()
                        tidb_version = "v8.5.0-alpha"
                        time = sh(returnStdout: true, script: "date '+%Y%m%d'").trim()
                        tidb_version = "${tidb_version}-nightly-${time}"
                        RELEASE_BRANCH = "master"

                    }
                }
            } else if (DEBUG_MODE == "true") {
                tidb_version = "build-debug-mode"
            } else {
                tidb_version = RELEASE_TAG
            }

            // stage("Upload") {
            //     upload "package"
            // }

            stage("TiUP builds by products") {
                builds = [:]
                def paramsCDC = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${ticdc_sha1}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],

                ]

                stage("TiUP build cdc") {
                    retry(3) {
                        build(job: "cdc-tiup-mirror-update", wait: true, parameters: paramsCDC)
                    }
                }
                def paramsBR = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${br_sha1}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                ]

                stage("TiUP build br") {
                    retry(3) {
                        build(job: "br-tiup-mirror-update", wait: true, parameters: paramsBR)
                    }
                }
                def paramsDUMPLING = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${dumpling_sha1}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                ]
                stage("TiUP build dumpling") {
                    retry(3) {
                        build(job: "dumpling-tiup-mirror-update", wait: true, parameters: paramsDUMPLING)
                    }
                }
                // since 4.0.12 the same as br
                def paramsLIGHTNING = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${lightning_sha1}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                ]
                stage("TiUP build lightning") {
                    retry(3) {
                        build(job: "lightning-tiup-mirror-update", wait: true, parameters: paramsLIGHTNING)
                    }
                }
                def paramsTIFLASH = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${tiflash_sha1}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                ]
                stage("TiUP build tiflash") {
                    retry(3) {
                        build(job: "tiflash-tiup-mirror-update", wait: true, parameters: paramsTIFLASH)
                    }
                }
                stage("TiUP build grafana") {
                    def paramsGRANFANA = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${tiflash_sha1}"),
                        string(name: "RELEASE_BRANCH", value: "${RELEASE_BRANCH}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                    ]
                    retry(3) {
                        build(job: "grafana-tiup-mirror-update", wait: true, parameters: paramsGRANFANA)
                    }
                }
                stage("TiUP build prometheus") {
                    def paramsPROMETHEUS = [
                        string(name: "RELEASE_TAG", value: "${RELEASE_TAG}"),
                        string(name: "TIDB_VERSION", value: "${tidb_version}"),
                        string(name: "TIUP_MIRRORS", value: "${TIUP_MIRRORS}"),
                        string(name: "ORIGIN_TAG", value: "${tiflash_sha1}"),
                        string(name: "RELEASE_BRANCH", value: "${RELEASE_BRANCH}"),
                        [$class: 'BooleanParameterValue', name: 'ARCH_X86', value: params.ARCH_X86],
                        [$class: 'BooleanParameterValue', name: 'ARCH_ARM', value: params.ARCH_ARM],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC', value: params.ARCH_MAC],
                        [$class: 'BooleanParameterValue', name: 'ARCH_MAC_ARM', value: params.ARCH_MAC_ARM],
                    ]
                    retry(3) {
                        build(job: "prometheus-tiup-mirror-update", wait: true, parameters: paramsPROMETHEUS)
                    }
                }
            }
            stage("TiUP build tidb on linux/amd64") {
                run_with_pod {
                    dir("linux-amd64") {
                        retry(3) {
                            deleteDir()
                            sh """
                            sleep \$((RANDOM % 10))
                            """
                            update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "linux", "amd64"
                            update "tikv", RELEASE_TAG, tikv_sha1, "linux", "amd64"
                            update "pd", RELEASE_TAG, pd_sha1, "linux", "amd64"
                            update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "linux", "amd64"
                            if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                                update "dm", RELEASE_TAG, dm_sha1, "linux", "amd64"
                            }
                            update_ctl RELEASE_TAG, "linux", "amd64"
                            update "tidb", RELEASE_TAG, tidb_sha1, "linux", "amd64"
                        }
                    }
                }
            }
            stage("TiUP build tidb on linux/arm64") {
                run_with_pod {
                    dir("linux-arm64") {
                        retry(3) {
                            deleteDir()
                            sh """
                            sleep \$((RANDOM % 10))
                            """
                            update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "linux", "arm64"
                            update "tikv", RELEASE_TAG, tikv_sha1, "linux", "arm64"
                            update "pd", RELEASE_TAG, pd_sha1, "linux", "arm64"
                            update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "linux", "arm64"
                            if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                                update "dm", RELEASE_TAG, dm_sha1, "linux", "arm64"
                            }
                            update_ctl RELEASE_TAG, "linux", "arm64"
                            update "tidb", RELEASE_TAG, tidb_sha1, "linux", "arm64"
                        }
                    }
                }
            }
            stage("TiUP build tidb on darwin/amd64") {
                run_with_pod {
                    dir("darwin-amd64") {
                        retry(3) {
                            deleteDir()
                            sh """
                            sleep \$((RANDOM % 10))
                            """
                            update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "darwin", "amd64"
                            update "tikv", RELEASE_TAG, tikv_sha1, "darwin", "amd64"
                            update "pd", RELEASE_TAG, pd_sha1, "darwin", "amd64"
                            update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "darwin", "amd64"
                            // if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                            //     update "dm", HOTFIX_TAG, dm_sha1, "darwin", "amd64"
                            // }
                            update_ctl RELEASE_TAG, "darwin", "amd64"
                            update "tidb", RELEASE_TAG, tidb_sha1, "darwin", "amd64"
                        }
                    }
                }
            }
            stage("TiUP build tidb on darwin/arm64") {
                if (RELEASE_TAG >= "v5.1.0" || RELEASE_TAG == "nightly") {
                    run_with_pod {
                        dir("darwin-arm64") {
                            retry(3) {
                                deleteDir()
                                sh """
                                sleep \$((RANDOM % 10))
                                """
                                update "tidb-ctl", RELEASE_TAG, tidb_ctl_sha1, "darwin", "arm64"
                                update "tikv", RELEASE_TAG, tikv_sha1, "darwin", "arm64"
                                update "pd", RELEASE_TAG, pd_sha1, "darwin", "arm64"
                                update "tidb-binlog", RELEASE_TAG, tidb_binlog_sha1, "darwin", "arm64"
                                // if (RELEASE_TAG == "nightly" || RELEASE_TAG >= "v5.3.0") {
                                //     update "dm", RELEASE_TAG, dm_sha1, "darwin", "amd64"
                                // }
                                // using self built etcdctl
                                update_ctl RELEASE_TAG, "darwin", "arm64"
                                update "tidb", RELEASE_TAG, tidb_sha1, "darwin", "arm64"
                            }
                        }
                    }
                }else{
                    echo "skip for older version"
                }
            }
        }
    }
}
currentBuild.result = "SUCCESS"
}  catch (Exception e) {
    println "${e}"
    currentBuild.result = "FAILURE"
} finally {
    upload_pipeline_run_data()
}

def upload_pipeline_run_data() {
    stage("Upload pipeline run data") {
        taskFinishTimeInMillis = System.currentTimeMillis()
        build job: 'upload-pipeline-run-data-to-db',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_TYPE', value: "tiup online"],
                    [$class: 'StringParameterValue', name: 'STATUS', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_ID', value: "${BUILD_NUMBER}"],
                    [$class: 'StringParameterValue', name: 'JENKINS_RUN_URL', value: "${env.RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_REVOKER', value: "sre-bot"],
                    [$class: 'StringParameterValue', name: 'ERROR_CODE', value: "0"],
                    [$class: 'StringParameterValue', name: 'ERROR_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_END_TIME', value: "${taskFinishTimeInMillis}"],
            ]
    }
}

env.PRODUCED_VERSION = tidb_version
