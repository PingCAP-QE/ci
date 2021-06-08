properties([
    parameters([
        string(name: 'VERSION', defaultValue: 'master', description: '', trim: true),
        string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
        booleanParam(name: 'TIUP_PUBLISH', defaultValue:false, description:''),
    ])
])

def main() {
    stage("Publish") {
        container("golang") {
            retry(3){
                sh("""
                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
                chmod +x tici.sh
                curl -sL -o grafana.tar.gz https://tiup-mirrors.pingcap.com/grafana-v4.0.1-linux-amd64.tar.gz
                curl -sL -o prometheus.tar.gz https://tiup-mirrors.pingcap.com/prometheus-v4.0.1-linux-amd64.tar.gz
                """)
                sh(script: "./tici.sh tiup_publish $params.TIUP_MIRRORS grafana $params.VERSION grafana.tar.gz bin/grafana-server", returnStdout: true).trim()
                sh(script: "./tici.sh tiup_publish $params.TIUP_MIRRORS prometheus $params.VERSION prometheus.tar.gz prometheus/prometheus", returnStdout: true).trim()
            }
        }
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
        containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("grafana") { main() } } }
}

catchError {
    run('build-go1130', 'hub.pingcap.net/jenkins/centos7_golang-1.13') { main() }
}
