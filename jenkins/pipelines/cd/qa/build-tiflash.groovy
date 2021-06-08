properties([
        parameters([
                string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
                string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
                string(name: 'HASH', defaultValue: '', description: '', trim: true),
                string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/*', description: '', trim: true),
                string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
                booleanParam(name: 'TIUP_PUBLISH', defaultValue: false, description: ''),
                string(name: 'VERSION', defaultValue: '', description: '', trim: true),
        ])
])

def main() {
    def tag = params.BRANCH.replaceAll("/", "-")
    def hash = ""
    def branch = params.BRANCH
    if (params.FORK != "pingcap") {
        tag = "${tag}-${params.FORK}"
    }
    if (params.VERSION != "") {
        tag = "$params.VERSION"
    }
    def release = 1
    if (params.FORK == "pingcap") {
//        上传 tiflash 时候特殊处理，branch/hash hash 两种路径都包含
        def filepath = "http://fileserver.pingcap.net/download/builds/pingcap/tiflash/optimization/${params.BRANCH}/centos7/tiflash.tar.gz"
        release = sh(returnStatus: true, script: """
                    curl --output /dev/null --silent --head --fail ${filepath}
                    """)
        if (release == 0) {
            sh "curl ${filepath}| tar xz"
        }
    }
    if (release != 0) {
        stage("GetHash") {
            // naively decide whether BRANCH is a hash
            if (params.HASH.length() != 0) {
                hash = "$params.HASH" 
            } else {
                withCredentials([usernameColonPassword(credentialsId: params.getOrDefault("GH_CREDENTIAL", "github-sre-bot"), variable: "GH_AUTH")]) {
                    hash = sh(script: """
                    git config --global credential.helper store
                    echo "https://$GH_AUTH@github.com" > $HOME/.git-credentials
                    git ls-remote  https://github.com/$params.FORK/tics.git refs/heads/$branch | cut -f 1
                    """, returnStdout: true).trim()
                }
            }
        }
    }
    stage("Publish") {
        container("golang") {
            retry(3) {
                if (release != 0) {
                    sh """
                echo "hash = $hash"
                echo "branch = $branch"
                curl -sL -o tiflash.tar.gz http://fileserver.pingcap.net/download/builds/pingcap/tiflash/$branch/$hash/centos7/tiflash.tar.gz --fail
                """
                }
                sh("""
                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
                chmod +x tici.sh
                """)
                sh(script: "./tici.sh tiup_publish $params.TIUP_MIRRORS tiflash ${tag} tiflash.tar.gz tiflash/tiflash", returnStdout: true).trim()
            }
        }
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
            containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("tiflash") { main() } } }
}

catchError {
    run('build-go1164', 'hub.pingcap.net/pingcap/centos7_golang-1.16') { main() }
}
