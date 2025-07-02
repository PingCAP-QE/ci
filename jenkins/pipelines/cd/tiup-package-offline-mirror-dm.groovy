if(DEBUG_MODE == "true"){
    VERSION="build-debug-mode"
}
def install_tiup = { bin_dir ->
    sh """
    wget -qO tiup-linux-amd64.tar.gz https://tiup-mirrors.pingcap.com/tiup-v1.14.1-linux-amd64.tar.gz
    tar -zxf tiup-linux-amd64.tar.gz -C ${bin_dir}
    chmod 755 ${bin_dir}/tiup
    rm -rf ~/.tiup
    mkdir -p ~/.tiup/bin
    curl https://tiup-mirrors.pingcap.com/root.json -o ~/.tiup/bin/root.json
    """
}

def delivery = { arch ->
    def dst = "tidb-dm-" + VERSION + "-linux-" + arch
    if ( VERSION.startsWith("v")  && VERSION >= "v5.4.0" ) {
        sh """
        tiup mirror clone $dst --os linux --arch $arch \
            --dm-master $VERSION --dm-worker $VERSION --dmctl $VERSION \
            --alertmanager latest --grafana $VERSION --prometheus $VERSION \
            --tiup latest --dm latest
        tar -czf ${dst}.tar.gz $dst
        curl -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload

        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${dst}.tar.gz ${dst}.tar.gz

        #yes|cp -R /etc/.aws /root

        aws s3 cp ${dst}.tar.gz s3://download.pingcap.org/${dst}.tar.gz --acl public-read
        echo "upload $dst successed!"
        """
    }else {
        sh """
        tiup mirror clone $dst --os linux --arch $arch \
            --dm-master=$VERSION --dm-worker=$VERSION --dmctl=$VERSION \
            --alertmanager=v0.17.0 --grafana=v4.0.3 --prometheus=v4.0.3 \
            --tiup=${TIUP_VERSION} --dm=${TIUP_VERSION}
        tar -czf ${dst}.tar.gz $dst
        curl -F release/${dst}.tar.gz=@${dst}.tar.gz ${FILE_SERVER_URL}/upload

        export REQUESTS_CA_BUNDLE=/etc/ssl/certs/ca-bundle.crt
        upload.py ${dst}.tar.gz ${dst}.tar.gz
        aws s3 cp ${dst}.tar.gz s3://download.pingcap.org/${dst}.tar.gz --acl public-read
        echo "upload $dst successed!"
        """
    }
}

node("delivery") {
    container("delivery") {
        def ws = pwd()
        def user = sh(returnStdout: true, script: "whoami").trim()
        sh "rm -rf ./*"
        stage("prepare") {
            println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
            println "${ws}"
            println "${user}"
        }

        stage("install tiup") {
            install_tiup "/usr/local/bin"
        }

        stage("build tarball linux/amd64") {
            delivery("amd64")
        }

        stage("build tarball linux/arm64") {
            delivery("arm64")
        }
    }
}
