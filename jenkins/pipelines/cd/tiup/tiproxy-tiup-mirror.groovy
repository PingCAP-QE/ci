def proxy_desc = "tiproxy is a database proxy that is based on TiDB."

def update = { name, version, os, arch, garch, gversion ->
    sh """
    wget https://github.com/pingcap/tiproxy/releases/download/v${gversion}/tiproxy_${gversion}_${os}_${garch}.tar.gz
    tiup mirror publish tiproxy ${version} tiproxy_${gversion}_${os}_${garch}.tar.gz tiproxy --arch ${arch} --os ${os} --desc="${proxy_desc}"
    """
}

pipeline{
    parameters{
        string(name: 'VERSION', defaultValue: '0.1.1', description: 'tiproxy version')
        string(name: 'TIDB_VERSION', defaultValue: 'nightly', description: 'tiup package verion')
        string(name: 'TIUP_MIRRORS', defaultValue: 'https://tiup.pingcap.net:8987', description: 'tiup mirror')
    }
    agent {
        kubernetes {
            yaml '''
spec:
  containers:
  - name: tiup
    image: hub.pingcap.net/jenkins/tiup
    args: ["sleep", "infinity"]
'''
          defaultContainer 'tiup'
        }
    }
    stages {
        stage("prepare tiup"){
            environment { TIUPKEY_JSON = credentials('tiup-prod-key')}
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON /root/.tiup/keys/private.json'
            }
        }

        stage ("publish") {
            environment {
                TIUP_MIRRORS = "${params.TIUP_MIRRORS}"
            }
            parallel{
                stage("TiUP build tiproxy on linux/amd64") { steps { script {
                  update "tiproxy", params.TIDB_VERSION, "linux", "amd64", "amd64v3", params.VERSION
                }}}
                stage("TiUP build tiproxy on linux/arm64") { steps { script {
                  update "tiproxy", params.TIDB_VERSION, "linux", "arm64", "arm64", params.VERSION
                }}}
                stage("TiUP build tiproxy on darwin/amd64") { steps { script {
                  update "tiproxy", params.TIDB_VERSION, "darwin", "amd64", "amd64v3", params.VERSION
                }}}
                stage("TiUP build tiproxy on darwin/arm64") { steps { script {
                  update "tiproxy", params.TIDB_VERSION, "darwin", "arm64", "arm64", params.VERSION
                }}}
            }
        }
    }
}
