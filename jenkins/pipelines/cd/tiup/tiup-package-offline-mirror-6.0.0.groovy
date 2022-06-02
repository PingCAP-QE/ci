release_tag = params.VERSION
source = FILE_SERVER_URL
if (DEBUG_MODE == "true") {
    VERSION = "build-debug-mode"
    release_tag = params.RELEASE_BRANCH
    source = "github"
}

def clone_server_package = { arch, dst ->
    sh """
    tiup mirror set https://tiup-mirrors.pingcap.com
    tiup mirror clone $dst --os linux --arch ${arch} --tidb $VERSION --tikv $VERSION \
    --tiflash $VERSION --pd $VERSION --ctl $VERSION --grafana $VERSION --alertmanager v0.17.0 \
    --blackbox_exporter v0.12.0 --prometheus $VERSION --node_exporter v0.17.0 \
    --tiup v1.9.4 --cluster v1.9.4  --insight v0.4.1 --diag v0.7.0 --influxdb v1.8.9 \
    --playground v1.9.4
    """
}

def clone_toolkit_package = { arch, dst ->
    sh """
    tiup mirror set https://tiup-mirrors.pingcap.com
    tiup mirror clone $dst --os linux --arch ${arch} --tikv-importer v4.0.2 --pd-recover $VERSION \
    --tiup v1.9.4 --tidb-lightning $VERSION --dumpling $VERSION --cdc $VERSION --dm-worker $VERSION \
    --dm-master $VERSION --dmctl $VERSION --dm v1.9.4 --br $VERSION --spark v2.4.3 \
    --tispark v2.4.1 --package v0.0.9  --bench v1.9.4 --errdoc v4.0.7 --dba v1.0.4 \
    --PCC 1.0.1 --pump $VERSION --drainer $VERSION 
    """
}

def package_community = { arch ->
    def dst = "tidb-community-server-" + VERSION + "-linux-" + arch

    clone_server_package(arch, dst)


    sh """
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'

    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
    upload.py ${dst}.tar.gz ${dst}.tar.gz
    aws s3 cp ${dst}.tar.gz s3://download.pingcap.org/${dst}.tar.gz --acl public-read
    echo "upload $dst successed!"
    """
}

def package_enterprise = { arch ->
    def comps = ["tidb", "tikv", "pd"]
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    def hashes = [:]
    comps.each {
        hashes[it] = sh(returnStdout: true, script: "python gethash.py -repo=${it} -version=${release_tag} -s=${source}").trim()
    }
    def os = "linux"
    def dst = "tidb-enterprise-server-" + VERSION + "-linux-" + arch
    def descs = [
            "tidb": "TiDB is an open source distributed HTAP database compatible with the MySQL protocol",
            "tikv": "Distributed transactional key-value database, originally created to complement TiDB",
            "pd"  : "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster",
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
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/optimization/${release_tag}/${hashes[it]}/centos7/${it}-${os}-${arch}-enterprise.tar.gz
        tar -xzf ${it}-${os}-${arch}-enterprise.tar.gz
        tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C bin/ \$(ls bin/)
        """

        sh """
        tiup mirror publish ${it} ${VERSION} ${it}-server-linux-${arch}-enterprise.tar.gz ${it}-server --arch ${arch} --os ${os} --desc="${descs[it]}"
        """
    }

    def tiflash_hash = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${release_tag} -s=${source}").trim()
    sh """
    wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${release_tag}/${tiflash_hash}/centos7/tiflash-${os}-${arch}-enterprise.tar.gz
    rm -rf tiflash
    tar -xzf tiflash-linux-${arch}-enterprise.tar.gz
    rm -rf tiflash-linux-${arch}-enterprise.tar.gz
    tar -czf tiflash-${VERSION}-linux-${arch}.tar.gz tiflash
    tiup mirror publish tiflash ${VERSION} tiflash-${VERSION}-linux-${arch}.tar.gz tiflash/tiflash --arch ${arch} --os linux --desc="The TiFlash Columnar Storage Engine"
    """

    sh """
    echo '\$bin_dir/tiup telemetry disable &> /dev/null' >> $dst/local_install.sh
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
    echo "upload $dst successed!"
    """
}

def package_tools = { plat, arch ->
    def toolkit_dir = "tidb-${plat}-toolkit-" + VERSION + "-linux-" + arch
    def tidb_dir = "tidb-${VERSION}-linux-${arch}"
    def tidb_toolkit_dir = "tidb-toolkit-${VERSION}-linux-${arch}"

    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

    binlog_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${release_tag} -s=${source}").trim()
    pd_hash = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${release_tag} -s=${source}").trim()
    tools_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${release_tag} -s=${source}").trim()
    br_hash = ""
    if (VERSION >= "v5.2.0") {
        br_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${release_tag} -s=${source}").trim()
    } else {
        br_hash = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${release_tag} -s=${source}").trim()
    }
    mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()

    clone_toolkit_package(arch, toolkit_dir)

    sh """
        mkdir -p ${toolkit_dir}/
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${release_tag}/${binlog_hash}/centos7/tidb-binlog-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${release_tag}/${pd_hash}/centos7/pd-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${release_tag}/${tools_hash}/centos7/tidb-tools-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${release_tag}/${br_hash}/centos7/br-linux-${arch}.tar.gz
        if [ ${arch} == 'amd64' ]; then
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/mydumper/${mydumper_sha1}/centos7/mydumper-linux-${arch}.tar.gz
        fi;
        wget -qnc ${FILE_SERVER_URL}/download/pingcap/etcd-v3.3.10-linux-${arch}.tar.gz


        tar xf tidb-binlog-linux-${arch}.tar.gz
        tar xf pd-linux-${arch}.tar.gz
        tar xf tidb-tools-linux-${arch}.tar.gz
        tar xf br-linux-${arch}.tar.gz
        if [ ${arch} == 'amd64' ]; then
            tar xf mydumper-linux-${arch}.tar.gz 
        fi;
        tar xf etcd-v3.3.10-linux-${arch}.tar.gz

        
        cp bin/binlogctl ${toolkit_dir}/
        cp bin/sync_diff_inspector ${toolkit_dir}/
        cp bin/reparo ${toolkit_dir}/
        cp bin/arbiter ${toolkit_dir}/
        cp bin/tidb-lightning-ctl ${toolkit_dir}/
        if [ ${arch} == 'amd64' ]; then
            cp mydumper-linux-${arch}/bin/mydumper ${toolkit_dir}/
        fi;
        cp etcd-v3.3.10-linux-${arch}/etcdctl ${toolkit_dir}/
        
        tar czvf ${toolkit_dir}.tar.gz ${toolkit_dir}
        curl --fail -F release/${toolkit_dir}.tar.gz=@${toolkit_dir}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
    """

    if (plat == "community") {
        sh """
        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${toolkit_dir}.tar.gz ${toolkit_dir}.tar.gz
        aws s3 cp ${toolkit_dir}.tar.gz s3://download.pingcap.org/${toolkit_dir}.tar.gz --acl public-read
        """
    }
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
            if (release_tag >= "v4" || DEBUG_MODE == "true") {
                package_tools "community", "amd64"
            }
        }

        stage("build community tarball linux/arm64") {
            package_community("arm64")
            if (release_tag >= "v4" || DEBUG_MODE == "true") {
                package_tools "community", "arm64"
            }
        }

        def noEnterpriseList = ["v4.0.0", "v4.0.1", "v4.0.2"]
        if ((release_tag >= "v4" && !noEnterpriseList.contains(release_tag)) || DEBUG_MODE == "true") {
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
