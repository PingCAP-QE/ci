package cd

final  tiupYaml='''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: tiup
    image: hub.pingcap.net/jenkins/tiup
    args: ["sleep", "infinity"]
'''

pipeline {
    agent none
    parameters {
        string(name: 'ReleaseTag', defaultValue: 'test', description: 'empty means the same with GitRef')
    }
    stages {
        stage("publish tiup"){
            agent{
                kubernetes{
                    yaml tiupYaml
                    defaultContainer 'tiup'
                }
            }
            environment {TIUP_MIRRORS = 'http://tiup.pingcap.net:8987'; TIUPKEY_JSON = credentials('tiup-prod-key') }
            steps{
                sh 'set +x;curl https://tiup-mirrors.pingcap.com/root.json -o /root/.tiup/bin/root.json; mkdir -p /root/.tiup/keys; cp $TIUPKEY_JSON  /root/.tiup/keys/private.json'
                sh """
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/tidb-dashboard-linux-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/tidb-dashboard-linux-arm64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/tidb-dashboard-darwin-amd64.tar.gz
                    curl -O http://fileserver.pingcap.net/download/builds/tidb-dashboard/${params.ReleaseTag}/tidb-dashboard-darwin-arm64.tar.gz
                   """
                sh """
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-linux-amd64.tar.gz tidb-dashboard --os=linux --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-linux-arm64.tar.gz tidb-dashboard --os=linux --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-darwin-amd64.tar.gz tidb-dashboard --os=darwin --arch=amd64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                    tiup mirror publish tidb-dashboard ${ReleaseTag} tidb-dashboard-darwin-arm64.tar.gz tidb-dashboard --os=darwin --arch=arm64 --desc='TiDB Dashboard is a Web UI for monitoring, diagnosing, and managing the TiDB cluster'
                   """
            }
        }
        stage("publish image"){
            agent none
            steps{
                build(job: "jenkins-image-syncer",
                    parameters: [
                        string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/rc/tidb-dashboard:${ReleaseTag}"),
                        string(name: 'TARGET_IMAGE', value: "pingcap/tidb-dashboard:${ReleaseTag}"),
                    ],
                    wait: true)
            }
        }
    }
}
