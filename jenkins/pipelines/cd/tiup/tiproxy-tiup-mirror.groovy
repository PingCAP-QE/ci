def proxy_desc = "TiProxy is a database proxy that is based on TiDB."

def update = { name, version, os, arch, garch, gversion ->
    sh """
    wget https://github.com/pingcap/TiProxy/releases/download/v${gversion}/TiProxy_${gversion}_${os}_${garch}.tar.gz
    tiup mirror publish tiproxy ${version} TiProxy-${gversion}-${os}-${garch}.tar.gz tiproxy --arch ${arch} --os ${os} --desc="${proxy_desc}"
    """
}

pipeline{
    parameters{
        string(name: 'VERSION', defaultValue: '0.1.1', description: 'tiproxy version')
        string(name: 'TIDB_VERSION', defaultValue: '', description: 'tiup package verion')
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
            when {expression {!fallback}}
            environment { TIUPKEY_JSON = credentials('tiup-prod-key')}
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON /root/.tiup/keys/private.json'
            }
        }

        if (TIDB_VERSION == "nightly" || TIDB_VERSION >= "v6.4.0") {
            stage("TiUP build tiproxy on linux/amd64") {
                update "tiproxy", params.TIDB_VERSION, "linux", "amd64", "amd64v3", params.VERSION
            }
            stage("TiUP build tiproxy on linux/arm64") {
                update "tiproxy", params.TIDB_VERSION, "linux", "arm64", "arm64", params.VERSION
            }
            stage("TiUP build tiproxy on darwin/amd64") {
                update "tiproxy", params.TIDB_VERSION, "darwin", "amd64", "amd64v3", params.VERSION
            }
            stage("TiUP build tiproxy on darwin/arm64") {
                update "tiproxy", params.TIDB_VERSION, "darwin", "arm64", "arm64", params.VERSION
            }
        }
    }
}
