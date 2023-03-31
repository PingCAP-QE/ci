pipeline{
    parameters{
        string(name: 'FileURL', description: 'br file url')
        string(name: 'Version', description: 'br verison in tiup')
    }
    agent{
        kubernetes{
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
    stages{
        stage("push staging"){
            environment {TIUP_MIRRORS = 'http://172.16.5.139:8988'; TIUPKEY_JSON = credentials('tiup-prod-key') }
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                sh """
                    set -e
                    curl --fail -o br $FileURL
                    tar -czvf br.tar.gz br
                    tiup mirror publish br ${Version} br.tar.gz br --os=linux --arch=amd64 --desc='TiDB/TiKV cluster backup restore tool'
                   """
            }
        }
    }
}
