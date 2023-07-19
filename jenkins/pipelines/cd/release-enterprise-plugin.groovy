final uploaderYaml = '''
spec:
  containers:
  - name: uploader
    image: hub.pingcap.net/jenkins/uploader
    args: ["sleep", "infinity"]
'''
pipeline{
    parameters {
        string(name: 'Version', description: 'important, the version for cli --version and profile choosing, eg. v6.5.0')
        string(name: 'Hash', description: 'git hash')
        booleanParam(name: 'IsLTS', description: 'whether it is a lts version')
    }
    agent {
        kubernetes {
            yaml uploaderYaml
            defaultContainer 'uploader'
        }
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
                    stage("upload fileserver"){
                        steps{
                            sh """
                            wget -q http://fileserver.pingcap.net/download/builds/pingcap/enterprise-plugin/optimization/${params.Version}/${params.Hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz
                            wget -q http://fileserver.pingcap.net/download/builds/pingcap/enterprise-plugin/optimization/${params.Version}/${params.Hash}/centos7/enterprise-plugin-linux-${arch}-enterprise.tar.gz.sha256
                            curl --fail -F release/enterprise-plugin-${params.Version}-linux-${arch}.tar.gz=@enterprise-plugin-linux-${arch}-enterprise.tar.gz http://fileserver.pingcap.net/upload
                            curl --fail -F release/enterprise-plugin-${params.Version}-linux-${arch}.tar.gz.sha256=@enterprise-plugin-linux-${arch}-enterprise.tar.gz.sha256 http://fileserver.pingcap.net/upload
                            """
                        }
                    }
                    stage("upload public"){
                        when {equals expected:true, actual:params.IsLTS.toBoolean()}
                        environment {
                            QINIU_BUCKET_NAME = 'tidb'
                            QINIU_ACCESS_KEY = credentials('qn_access_key');
                            QINIU_SECRET_KEY = credentials('qiniu_secret_key');
                        }
                        steps{
                            sh """
                            upload_qiniu.py enterprise-plugin-linux-${arch}-enterprise.tar.gz enterprise-plugin-${params.Version}-linux-${arch}.tar.gz
                            upload_qiniu.py enterprise-plugin-linux-${arch}-enterprise.tar.gz.sha256 enterprise-plugin-${params.Version}-linux-${arch}.tar.gz.sha256
                            """
                        }
                    }
                }
            }
        }
    }
}
