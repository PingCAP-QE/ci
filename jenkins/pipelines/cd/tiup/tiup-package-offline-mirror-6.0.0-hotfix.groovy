def clone_server_package = { arch, dst ->
    sh """
    tiup mirror set http://staging.tiup-server.pingcap.net
    tiup mirror clone $dst --os linux --arch ${arch} --tidb $VERSION --tikv $VERSION \
    --tiflash $VERSION --pd $VERSION --ctl $VERSION --grafana $VERSION --alertmanager v0.17.0 \
    --blackbox_exporter v0.12.0 --prometheus $VERSION --node_exporter v0.17.0 \
    --tiup v1.9.2 --cluster v1.9.2  --insight v0.4.1 --diag v0.7.0 --influxdb v1.8.9 \
    --playground v1.9.2
    """
}

def clone_toolkit_package = { arch, dst ->
    sh """
    tiup mirror set http://staging.tiup-server.pingcap.net
    tiup mirror clone $dst --os linux --arch ${arch} --tikv-importer v4.0.2 --pd-recover $VERSION \
    --tiup v1.9.2 --tidb-lightning $VERSION --dumpling $VERSION --cdc $VERSION --dm-worker $VERSION \
    --dm-master $VERSION --dmctl $VERSION --dm v1.9.2 --br $VERSION --spark v2.4.3 \
    --tispark v2.4.1 --package v0.0.9  --bench v1.9.2 --errdoc v4.0.7 --dba v1.0.4 \
    --PCC 1.0.1 --pump $VERSION --drainer $VERSION
    """
}

def package_community = { arch ->
    def dst = "tidb-community-server-" + VERSION + "-pre" + "-linux-" + arch

    clone_server_package(arch, dst)

    sh """
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
    """
}

def package_enterprise = { arch ->
    def comps = ["tidb", "tikv", "pd"]
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    def hashes = [:]
    hashes["tidb"] = "6f28ac47fa43565d57e47fd23946389cac4e3fd2"
    hashes["tikv"] = "769a997e4f42d5eea6c607a6ed82a1f7b9dcb61a"
    hashes["pd"] = "942b6422c2329d91f0fefa0cff17e27791a5ad3f"
    def os = "linux"
    def dst = "tidb-enterprise-server-" + VERSION + "-pre" + "-linux-" + arch
    def descs = [
            "tidb": "TiDB is an open source distributed HTAP database compatible with the MySQL protocol",
            "tikv": "Distributed transactional key-value database, originally created to complement TiDB",
            "pd": "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster",
    ]

    clone_server_package(arch, dst)
    sh """
    tiup mirror set ${dst}
    cp ${dst}/keys/*-pingcap.json ~/.tiup/keys/private.json
    """

    comps.each {
        sh """
        rm -rf bin
        """

        sh """
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/optimization/${version}/${hashes[it]}/centos7/${it}-${os}-${arch}-enterprise.tar.gz
        tar -xzf ${it}-${os}-${arch}-enterprise.tar.gz
        tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C bin/ \$(ls bin/)
        """

        sh """
        tiup mirror publish ${it} ${VERSION} ${it}-server-linux-${arch}-enterprise.tar.gz ${it}-server --arch ${arch} --os ${os} --desc="${descs[it]}"
        """
    }

    def tiflash_hash = "25545c452b21a5e545f7d8237364076ed2a057ec"
    sh """
    wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${VERSION}/${tiflash_hash}/centos7/tiflash-${os}-${arch}-enterprise.tar.gz
    rm -rf tiflash
    tar -xzf tiflash-linux-${arch}-enterprise.tar.gz
    rm -rf tiflash-linux-${arch}-enterprise.tar.gz
    tar -czf tiflash-${VERSION}-linux-${arch}.tar.gz tiflash
    tiup mirror publish tiflash ${VERSION} tiflash-${VERSION}-linux-${arch}.tar.gz tiflash/tiflash --arch ${arch} --os linux --desc="The TiFlash Columnar Storage Engine"
    """

    sh """
    echo '\$bin_dir/tiup telemetry disable &> /dev/null' >> $dst/local_install.sh
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
    echo "upload $dst successed!"
    """
}

def package_tools = { plat, arch ->
    def toolkit_dir = "tidb-${plat}-toolkit-" + VERSION + "-pre" + "-linux-" + arch
    def tidb_dir = "tidb-${VERSION}-linux-${arch}"
    def tidb_toolkit_dir = "tidb-toolkit-${VERSION}-linux-${arch}"

    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

    binlog_hash = "51b6c0dacd5c745de6ed48c22a45db0fac8dfe21"
    pd_hash = "942b6422c2329d91f0fefa0cff17e27791a5ad3f"
    tools_hash = "4dd10bca555120999184caed775ac081d1c2dc4a"
    br_hash = "6f28ac47fa43565d57e47fd23946389cac4e3fd2"
    if (VERSION >= "v5.2.0") {
        br_hash = "6f28ac47fa43565d57e47fd23946389cac4e3fd2"
    } else {
        br_hash = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    }
    mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()

    clone_toolkit_package(arch, toolkit_dir)

    sh """
        mkdir -p ${toolkit_dir}/
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${VERSION}/${binlog_hash}/centos7/tidb-binlog-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${VERSION}/${pd_hash}/centos7/pd-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${VERSION}/${tools_hash}/centos7/tidb-tools-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${VERSION}/${br_hash}/centos7/br-linux-${arch}.tar.gz
        if [ ${arch} == 'amd64' ]; then
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-${arch}.tar.gz
        fi;
        wget -qnc ${FILE_SERVER_URL}/download/pingcap/etcd-v3.4.30-linux-${arch}.tar.gz


        tar xf tidb-binlog-linux-${arch}.tar.gz
        tar xf pd-linux-${arch}.tar.gz
        tar xf tidb-tools-linux-${arch}.tar.gz
        tar xf br-linux-${arch}.tar.gz
        if [ ${arch} == 'amd64' ]; then
            tar xf mydumper-linux-${arch}.tar.gz
        fi;
        tar xf etcd-v3.4.30-linux-${arch}.tar.gz


        cp bin/binlogctl ${toolkit_dir}/
        cp bin/sync_diff_inspector ${toolkit_dir}/
        cp bin/reparo ${toolkit_dir}/
        cp bin/arbiter ${toolkit_dir}/
        cp bin/tidb-lightning-ctl ${toolkit_dir}/
        if [ ${arch} == 'amd64' ]; then
            cp mydumper-linux-${arch}/bin/mydumper ${toolkit_dir}/
        fi;
        cp etcd-v3.4.30-linux-${arch}/etcdctl ${toolkit_dir}/

        tar czvf ${toolkit_dir}.tar.gz ${toolkit_dir}
        curl --fail -F release/${toolkit_dir}.tar.gz=@${toolkit_dir}.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
    """
}

node("delivery") {
    container("delivery") {
        def ws = pwd()
        def user = sh(returnStdout: true, script: "whoami").trim()

        sh "find . -maxdepth 1 ! -path . -exec rm -rf {} +"

        stage("prepare") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            println "${user}"
        }

        checkout scm
        def util = load "jenkins/pipelines/cd/tiup/tiup_utils.groovy"

        stage("Install tiup") {
            util.install_tiup_without_key "/usr/local/bin"
        }

        stage("build community tarball linux/amd64") {
            deleteDir()
            package_community("amd64")
            if(VERSION >= "v4") {
                package_tools "community", "amd64"
            }
        }

        stage("build community tarball linux/arm64") {
            package_community("arm64")
            if(VERSION >= "v4") {
                package_tools "community", "arm64"
            }
        }

        def noEnterpriseList = ["v4.0.0", "v4.0.1", "v4.0.2"]
        if(VERSION >= "v4" && !noEnterpriseList.contains(VERSION)) {
            stage("build enterprise tarball linux/amd64") {
                package_enterprise("amd64")
                package_tools "enterprise", "amd64"
            }

            stage("build enterprise tarball linux/arm64") {
                package_enterprise("arm64")
                package_tools "enterprise", "arm64"
            }
        }
    }
}
