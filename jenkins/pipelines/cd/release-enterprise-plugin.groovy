pipeline{
    agent {label 'delivery'}
    parameters {
        string(name: 'Version', description: 'important, the version for cli --version and profile choosing, eg. v6.5.0')
        string(name: 'Hash', description: 'git hash')
    }
    stages{
        stage("multi-arch"){
            matrix{
                axes{
                    axis{
                        name "arch"
                        values "amd64", "arm64"
                    }
                }
                stages{
                stage("upload"){
                    steps{
                        container('delivery'){
                sh """
                wget -q http://fileserver.pingcap.net/download/builds/pingcap/enterprise-plugin/optimization/${params.Version}/${params.Hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz
                wget -q http://fileserver.pingcap.net/download/builds/pingcap/enterprise-plugin/optimization/${params.Version}/${params.Hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz.sha256
                curl --fail -F release/enterprise-plugin-${params.Version}-linux-${arch}.tar.gz=@enterprise-plugin-linux-${arch}-enterprise.tar.gz http://fileserver.pingcap.net/upload
                curl --fail -F release/enterprise-plugin-${params.Version}-linux-${arch}.tar.gz.sha256=@enterprise-plugin-linux-${arch}-enterprise.tar.gz.sha256 http://fileserver.pingcap.net/upload
                upload.py enterprise-plugin-linux-${arch}-enterprise.tar.gz enterprise-plugin-${params.Version}-linux-${arch}.tar.gz
                upload.py enterprise-plugin-linux-${arch}-enterprise.tar.gz enterprise-plugin-${params.Version}-linux-${arch}.tar.gz.sha256
                """
                        }
                    }
                }
                }
            }
        }
    }
}
