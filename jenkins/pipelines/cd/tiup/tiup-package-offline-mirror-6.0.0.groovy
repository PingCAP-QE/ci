release_tag = params.VERSION
release_tag_actual = params.VERSION
if (DEBUG_MODE == "true") {
    VERSION = "build-debug-mode"
    release_tag = params.RELEASE_BRANCH

}

lts_versions = ["v6.1","v6.5"]

def is_lts_version = { version ->
    for (lts_version in lts_versions) {
        if (version.contains(lts_version)) {
            println "is lts version: $version"
            return true
        }
    }
    println "is not lts version: $version"
    return false
}

def get_hash = { repo ->
    if (DEBUG_MODE == "true") {
        if(repo=="tidb-tools"){
            return sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb-tools/master/sha1").trim()
        }else{
            return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${release_tag} -source=github").trim()
        }
    } else {
        return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${release_tag} -s=${FILE_SERVER_URL}").trim()
    }
}

def clone_server_package = { arch, dst ->
    sh """
    tiup mirror set https://tiup-mirrors.pingcap.com
    tiup mirror clone $dst --os linux --arch ${arch} --tidb $VERSION --tikv $VERSION \
    --tiflash $VERSION --pd $VERSION --ctl $VERSION --grafana $VERSION --alertmanager latest \
    --blackbox_exporter latest --prometheus $VERSION --node_exporter latest \
    --tiup latest --cluster latest  --insight latest --diag latest --influxdb latest \
    --playground latest
    """
}

def clone_toolkit_package = { arch, dst ->
    // Add some monitor tools to the toolkit package for offline mirror >= v6.1.1
    // TODO: which package is server, --cluster latest?
    // issue : https://github.com/PingCAP-QE/ci/issues/1256
    if (release_tag >= "v6.1.1") {
        sh """
        tiup mirror set https://tiup-mirrors.pingcap.com
        tiup mirror clone $dst --os linux --arch ${arch} --tikv-importer v4.0.2 --pd-recover $VERSION \
        --tiup latest --tidb-lightning $VERSION --dumpling $VERSION --cdc $VERSION --dm-worker $VERSION \
        --dm-master $VERSION --dmctl $VERSION --dm latest --br $VERSION --spark latest \
        --grafana $VERSION --alertmanager latest \
        --blackbox_exporter latest --prometheus $VERSION --node_exporter latest \
        --tispark latest --package latest  --bench latest --errdoc latest --dba latest \
        --PCC latest --pump $VERSION --drainer $VERSION --server latest
        """
    } else {
        sh """
        tiup mirror set https://tiup-mirrors.pingcap.com
        tiup mirror clone $dst --os linux --arch ${arch} --tikv-importer v4.0.2 --pd-recover $VERSION \
        --tiup latest --tidb-lightning $VERSION --dumpling $VERSION --cdc $VERSION --dm-worker $VERSION \
        --dm-master $VERSION --dmctl $VERSION --dm latest --br $VERSION --spark latest \
        --tispark latest --package latest  --bench latest --errdoc latest --dba latest \
        --PCC latest --pump $VERSION --drainer $VERSION 
        """
    }
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
    sh "sha256sum ${dst}.tar.gz"
}

def package_enterprise = { arch ->
    def comps = ["tidb", "tikv", "pd"]
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"
    def hashes = [:]
    comps.each {
        hashes[it] = get_hash("${it}")
        println "${it}: ${hashes[it]}"
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
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/optimization/${release_tag_actual}/${hashes[it]}/centos7/${it}-${os}-${arch}-enterprise.tar.gz
        tar -xzf ${it}-${os}-${arch}-enterprise.tar.gz
        tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C bin/ \$(ls bin/)
        """

        sh """
        tiup mirror publish ${it} ${VERSION} ${it}-server-linux-${arch}-enterprise.tar.gz ${it}-server --arch ${arch} --os ${os} --desc="${descs[it]}"
        """
    }
    def tiflash_hash = get_hash("tics")

    sh """
    wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/optimization/${release_tag_actual}/${tiflash_hash}/centos7/tiflash-${os}-${arch}-enterprise.tar.gz
    rm -rf tiflash
    tar -xzf tiflash-linux-${arch}-enterprise.tar.gz
    rm -rf tiflash-linux-${arch}-enterprise.tar.gz
    tar -czf tiflash-${VERSION}-linux-${arch}.tar.gz tiflash
    tiup mirror publish tiflash ${VERSION} tiflash-${VERSION}-linux-${arch}.tar.gz tiflash/tiflash --arch ${arch} --os linux --desc="The TiFlash Columnar Storage Engine"
    """
    if (release_tag >= "v6.1.0") {
        println "current release_tag is ${release_tag}, need to remove commits dir after v6.1.0 tiup enterprise offline server package"
        // remove the useless files
        // releative issue : https://github.com/PingCAP-QE/ci/issues/1254
        sh """
        cd ${dst}
        ls commits/ || true
        rm -rf commits/
        cd -
        """
    }

    sh """
    echo '\$bin_dir/tiup telemetry disable &> /dev/null' >> $dst/local_install.sh
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep '"status":\\s*true\\b'
    echo "upload $dst successed!"
    """

    sh "sha256sum ${dst}.tar.gz"

    if (is_lts_version(release_tag)) {
        println "This is LTS version, need upload enterprise package to aws s3"
        sh """
        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${dst}.tar.gz ${dst}.tar.gz
        aws s3 cp ${dst}.tar.gz s3://download.pingcap.org/${dst}.tar.gz --acl public-read
        echo "upload $dst successed!"
        """
    }
}

def package_tools = { plat, arch ->
    def toolkit_dir = "tidb-${plat}-toolkit-" + VERSION + "-linux-" + arch
    def tidb_dir = "tidb-${VERSION}-linux-${arch}"
    def tidb_toolkit_dir = "tidb-toolkit-${VERSION}-linux-${arch}"

    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

    def binlog_hash = get_hash("tidb-binlog")
    def pd_hash = get_hash("pd")
    def tools_hash = get_hash("tidb-tools")
    def br_hash
    if (release_tag_actual >= "v5.2.0") {
        br_hash = get_hash("tidb")
    } else {
        br_hash = get_hash("br")
    }
    def mydumper_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/mydumper/master/sha1").trim()

    println "binlog_hash: ${binlog_hash}"
    println "pd_hash: ${pd_hash}"
    println "tools_hash: ${tools_hash}"
    println "br_hash: ${br_hash}"
    println "mydumper_sha1: ${mydumper_sha1}"

    clone_toolkit_package(arch, toolkit_dir)

    sh """
        mkdir -p ${toolkit_dir}/
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${release_tag_actual}/${binlog_hash}/centos7/tidb-binlog-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${release_tag_actual}/${pd_hash}/centos7/pd-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${release_tag_actual}/${tools_hash}/centos7/tidb-tools-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${release_tag_actual}/${br_hash}/centos7/br-linux-${arch}.tar.gz
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

    sh "sha256sum ${toolkit_dir}.tar.gz"

    if (plat == "community") {
        sh """
        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${toolkit_dir}.tar.gz ${toolkit_dir}.tar.gz
        aws s3 cp ${toolkit_dir}.tar.gz s3://download.pingcap.org/${toolkit_dir}.tar.gz --acl public-read
        """
    }
    if (is_lts_version(release_tag) && plat == "enterprise" ) { 
        println "This is LTS version, need upload enterprise toolkit package to aws s3"
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
            if (release_tag_actual >= "v4") {
                package_tools "community", "arm64"
            }
        }

        def noEnterpriseList = ["v4.0.0", "v4.0.1", "v4.0.2"]
        if (release_tag_actual >= "v4" && !noEnterpriseList.contains(release_tag_actual)) {
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
