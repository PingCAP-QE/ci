

def cloned = [
        "amd64": "",
        "arm64": "",
]

def clone_package = { arch, dst ->
    if(cloned[arch] != "") {
        sh """
        mv ${cloned[arch]} ${dst}
        """
        cloned[arch] = dst
        return
    }

    sh """
    tiup mirror clone $dst $VERSION --os linux --arch ${arch} --alertmanager=v0.17.0 --grafana=v4.0.3 --prometheus=v4.0.3
    """
    cloned[arch] = dst
}

def package_community = { arch ->
    def dst = "tidb-community-server-" + VERSION + "-linux-" + arch

    clone_package(arch, dst)

    sh """
    tar -czf ${dst}.tar.gz $dst
    curl -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload
    
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
        hashes[it] = sh(returnStdout: true, script: "python gethash.py -repo=${it} -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    }
    def os = "linux"
    def dst = "tidb-enterprise-server-" + VERSION + "-linux-" + arch
    def descs = [
            "tidb": "TiDB is an open source distributed HTAP database compatible with the MySQL protocol",
            "tikv": "Distributed transactional key-value database, originally created to complement TiDB",
            "pd": "PD is the abbreviation for Placement Driver. It is used to manage and schedule the TiKV cluster",
    ]

    clone_package(arch, dst)
    sh """
    tiup mirror set ${dst}
    cp ${dst}/keys/*-pingcap.json ~/.tiup/keys/private.json
    """

    comps.each {
        sh """
        rm -rf bin
        """

        if(arch == "arm64") {
            sh """
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/${hashes[it]}/centos7/${it}-server-${version}-linux-arm64-enterprise.tar.gz
            tar -xzf ${it}-server-${version}-linux-arm64-enterprise.tar.gz 
            tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C ${it}-${version}-linux-arm64/bin/ \$(ls ${it}-${version}-linux-arm64/bin/)
            """
        } else {
            sh """
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/${hashes[it]}/centos7/${it}-server-${version}-enterprise.tar.gz
            tar -xzf ${it}-server-${version}-enterprise.tar.gz 
            tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C bin/ \$(ls bin/)
            """
        }

        sh """
        tiup mirror publish ${it} ${VERSION} ${it}-server-linux-${arch}-enterprise.tar.gz ${it}-server --arch ${arch} --os ${os} --desc="${descs[it]}"
        """
    }

    def tiflash_hash = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    if(arch == "amd64") {
        sh """
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${VERSION}/${tiflash_hash}/centos7/tiflash-${VERSION}-enterprise.tar.gz
        tar -xzf tiflash-${version}-enterprise.tar.gz
        rm -rf tiflash-${version}-enterprise.tar.gz
        tar -czf tiflash-${VERSION}-linux-${arch}.tar.gz tiflash
        tiup mirror publish tiflash ${VERSION} tiflash-${VERSION}-linux-${arch}.tar.gz tiflash/tiflash --arch ${arch} --os linux --desc="The TiFlash Columnar Storage Engine"
        """
    } else {
        sh """
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${VERSION}/${tiflash_hash}/centos7/tiflash-${VERSION}-linux-arm64-enterprise.tar.gz
        tar -xzf tiflash-${VERSION}-linux-arm64-enterprise.tar.gz
        rm -rf tiflash-${VERSION}-linux-arm64-enterprise.tar.gz
        mv tiflash-${VERSION}-linux-${arch} tiflash
        tar -czf tiflash-${VERSION}-linux-${arch}.tar.gz tiflash
        tiup mirror publish tiflash ${VERSION} tiflash-${VERSION}-linux-${arch}.tar.gz tiflash/tiflash --arch ${arch} --os linux --desc="The TiFlash Columnar Storage Engine"
        """
    }

    sh """
    echo '\$bin_dir/tiup telemetry disable &> /dev/null' >> $dst/local_install.sh
    tar -czf ${dst}.tar.gz $dst
    curl -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload
    echo "upload $dst successed!"
    """
}

def package_tools = { plat, arch ->
    def toolkit_dir = "tidb-${plat}-toolkit-" + VERSION + "-linux-" + arch
    def tidb_dir = "tidb-${VERSION}-linux-${arch}"
    def tidb_toolkit_dir = "tidb-toolkit-${VERSION}-linux-${arch}"

    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/gethash.py > gethash.py"

    binlog_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-binlog -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    pd_hash = sh(returnStdout: true, script: "python gethash.py -repo=pd -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    tools_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb-tools -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    br_hash = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    importer_hash = sh(returnStdout: true, script: "python gethash.py -repo=importer -version=${VERSION} -s=${FILE_SERVER_URL}").trim()

    if(arch == "arm64") {
        sh """
            mkdir -p ${toolkit_dir}/bin/
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${binlog_hash}/centos7/tidb-binlog-linux-arm64.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${pd_hash}/centos7/pd-server-linux-arm64.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${tools_hash}/centos7/tidb-tools-linux-arm64.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${VERSION}/${br_hash}/centos7/br-linux-arm64.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${importer_hash}/centos7/importer-linux-arm64.tar.gz

            tar xf tidb-binlog-linux-arm64.tar.gz
            tar xf pd-server-linux-arm64.tar.gz
            tar xf tidb-tools-linux-arm64.tar.gz
            tar xf br-linux-arm64.tar.gz
            tar xf importer-linux-arm64.tar.gz

            cd tidb-binlog-*-linux-arm64/bin/
            cp arbiter reparo ../../${toolkit_dir}/bin/

            cd ../../pd-v*-linux-arm64/bin/
            cp pd-recover pd-tso-bench ../../${toolkit_dir}/bin/

            cd ../../tidb-tools-v*-linux-arm64/bin
            cp sync_diff_inspector ../../${toolkit_dir}/bin/
            cd ../../br-v*-linux-arm64/bin
            cp tidb-lightning tidb-lightning-ctl ../../${toolkit_dir}/bin/

            cd ../../importer-v*-linux-arm64/bin
            cp tikv-importer ../../${toolkit_dir}/bin/

            cd ../../
            tar czvf ${toolkit_dir}.tar.gz ${toolkit_dir}
            curl -F release/${toolkit_dir}.tar.gz=@${toolkit_dir}.tar.gz ${FILE_SERVER_URL}/upload
        """
    } else if(arch == "amd64") {
        sh """
            mkdir -p ${toolkit_dir}/bin/
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${binlog_hash}/centos7/tidb-binlog.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${pd_hash}/centos7/pd-server.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${tools_hash}/centos7/tidb-tools.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${VERSION}/${br_hash}/centos7/br.tar.gz
            wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/importer/optimization/${importer_hash}/centos7/importer.tar.gz

            tar xf tidb-binlog.tar.gz
            tar xf pd-server.tar.gz
            tar xf tidb-tools.tar.gz
            tar xf br.tar.gz
            tar xf importer.tar.gz

            cd bin/
            cp arbiter reparo pd-recover pd-tso-bench sync_diff_inspector tidb-lightning tidb-lightning-ctl tikv-importer ../${toolkit_dir}/bin/

            cd ../
            tar czvf ${toolkit_dir}.tar.gz ${toolkit_dir}
            curl -F release/${toolkit_dir}.tar.gz=@${toolkit_dir}.tar.gz ${FILE_SERVER_URL}/upload
        """
    }

    if(plat == "community") {
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