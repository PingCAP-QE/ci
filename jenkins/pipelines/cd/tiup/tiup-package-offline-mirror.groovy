

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
    tiup mirror clone $dst $VERSION --os linux --arch ${arch}
    """
    cloned[arch] = dst
}

def package_community = { arch ->
    def dst = "tidb-community-server-" + VERSION + "-linux-" + arch

    clone_package(arch, dst)

    sh """
    tar -czf ${dst}.tar.gz $dst
    curl --fail -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'

    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
    upload.py ${dst}.tar.gz ${dst}.tar.gz
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

        sh """
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/${it}/optimization/${version}/${hashes[it]}/centos7/${it}-${os}-${arch}-enterprise.tar.gz
        tar -xzf ${it}-${os}-${arch}-enterprise.tar.gz
        tar -czf ${it}-server-linux-${arch}-enterprise.tar.gz -C bin/ \$(ls bin/)
        """

        sh """
        tiup mirror publish ${it} ${VERSION} ${it}-server-linux-${arch}-enterprise.tar.gz ${it}-server --arch ${arch} --os ${os} --desc="${descs[it]}"
        """
    }

    def tiflash_hash = sh(returnStdout: true, script: "python gethash.py -repo=tics -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
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

    sh """
    export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
    upload.py ${dst}.tar.gz ${dst}.tar.gz
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
    br_hash = ""
    if (VERSION >= "v5.2.0") {
        br_hash = sh(returnStdout: true, script: "python gethash.py -repo=tidb -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    } else {
        br_hash = sh(returnStdout: true, script: "python gethash.py -repo=br -version=${VERSION} -s=${FILE_SERVER_URL}").trim()
    }

    sh """
        mkdir -p ${toolkit_dir}/bin/
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-binlog/optimization/${VERSION}/${binlog_hash}/centos7/tidb-binlog-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/pd/optimization/${VERSION}/${pd_hash}/centos7/pd-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/tidb-tools/optimization/${VERSION}/${tools_hash}/centos7/tidb-tools-linux-${arch}.tar.gz
        wget -qnc ${FILE_SERVER_URL}/download/builds/pingcap/br/optimization/${VERSION}/${br_hash}/centos7/br-linux-${arch}.tar.gz


        tar xf tidb-binlog-linux-${arch}.tar.gz
        tar xf pd-linux-${arch}.tar.gz
        tar xf tidb-tools-linux-${arch}.tar.gz
        tar xf br-linux-${arch}.tar.gz

        cd bin/
        cp arbiter reparo pd-recover pd-tso-bench sync_diff_inspector tidb-lightning tidb-lightning-ctl ../${toolkit_dir}/bin/

        cd ../
        tar czvf ${toolkit_dir}.tar.gz ${toolkit_dir}
        curl --fail -F release/${toolkit_dir}.tar.gz=@${toolkit_dir}.tar.gz ${FILE_SERVER_URL}/upload | egrep 'success'
    """

    if(plat == "community" || plat == "enterprise") {
        sh """
        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${toolkit_dir}.tar.gz ${toolkit_dir}.tar.gz
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
