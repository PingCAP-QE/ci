properties([
        parameters([
                string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
                string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
                string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/*', description: '', trim: true),
                string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
                booleanParam(name: 'TIUP_PUBLISH', defaultValue: false, description: ''),
                choice(name: 'BUILD_METHOD', choices: ['go1.16.4-module', 'go1.13-module', 'go1.12-module', 'go1.11.2-module', 'go1.12-dep'], description: ''),
                booleanParam(name: 'FAILPOINT', defaultValue: false, description: ''),
                string(name: 'VERSION', defaultValue: '', description: '', trim: true),
        ])
])

def main(useDep) {
    def archiveURL = ''
    def tag = params.BRANCH.replaceAll('/', '-')
    if (params.FORK != 'pingcap') {
        tag = "${tag}-${params.FORK}"
    }
    if (params.FAILPOINT) {
        tag = "${tag}-failpoint"
    }
    if (params.VERSION != '') {
        tag = "$params.VERSION"
    }
    def release = 1
    if (params.FORK == 'pingcap' && !params.FAILPOINT) {
        def filepath = "http://fileserver.pingcap.net/download/builds/pingcap/pd/optimization/${params.BRANCH}/centos7/pd-server.tar.gz"
        release = sh(returnStatus: true, script: """
                    curl --output /dev/null --silent --head --fail ${filepath}
                    """)
        if (release == 0) {
            sh "curl ${filepath}| tar xz"
        }
    }
    if (release != 0) {
        stage('Checkout') {
            checkout(changelog: false, poll: false, scm: [
                    $class           : 'GitSCM',
                    branches         : [[name: params.BRANCH]],
                    userRemoteConfigs: [[url: "https://github.com/${params.FORK}/pd.git", refspec: params.REFSPEC]],
                    extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
            ])
        }
    }
    stage('Build') {
        container('golang') {
            if (useDep) {
                sh """
                wget -O dep https://github.com/golang/dep/releases/download/v0.5.4/dep-linux-amd64
                chmod +x ./dep && DEPPROJECTROOT=1 ./dep ensure -v
                ln -sf \$(pwd)/vendor /go/src
                """
            }

            if (params.FAILPOINT) {
                sh('make failpoint-enable')
            }
            if (release != 0) {
                sh('make')
            }
            retry(3) {
                sh("""
                curl -sL -o Dockerfile http://fileserver.pingcap.net/download/pingcap/qa/dockerfiles/pd/Dockerfilev2
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc

COPY entrypoint.sh /entrypoint.sh
COPY pd-server /bin
COPY pd-ctl /bin
COPY pd-recover /bin

RUN ln -sf /bin/pd-server /pd-server

ENTRYPOINT ["/entrypoint.sh"]

# hub.pingcap.net/qa/pd
__EOF__

                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
                chmod +x tici.sh
                curl -sL http://fileserver.pingcap.net/download/pingcap/qa/dockerfiles/common/entrypoint.sh | sed 's/__BIN__/pd-server/g' > entrypoint.sh && chmod +x entrypoint.sh
                """)
                archiveURL = sh(script: "./tici.sh publish $params.TIUP_PUBLISH $params.TIUP_MIRRORS pd-server pingcap/qa/archives pd ${tag} Dockerfile entrypoint.sh -C bin .", returnStdout: true).trim()
            }
            echo(archiveURL)
        }
    }
    stage('Image') {
        build(job: 'image-build', parameters: [
                string(name: 'CONTEXT_URL', value: archiveURL),
                string(name: 'DESTINATION', value: "hub.pingcap.net/qa/pd:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
            containerTemplate(name: 'golang', image: image, alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir('pd') { main() } } }
}

catchError {
    def label = 'build-go1130'
    def image = 'hub.pingcap.net/jenkins/centos7_golang-1.13'
    def useDep = false

    switch (params.BUILD_METHOD) {
        case 'go1.16.4-module':
            label = 'build-go1164'
            image = 'hub.pingcap.net/pingcap/centos7_golang-1.16'
            break
        case 'go1.13-module':
            label = 'build-go1130'
            image = 'hub.pingcap.net/jenkins/centos7_golang-1.13'
            break
        case 'go1.12-module':
            label = 'build-go1120'
            image = 'hub.pingcap.net/jenkins/centos7_golang-1.12'
            break
        case 'go1.11.2-module':
            label = 'build-go1112'
            image = 'hub.pingcap.net/jenkins/centos7_golang-1.11.2'
            break
        case 'go1.12-dep':
            label = 'build-go1120'
            image = 'hub.pingcap.net/jenkins/centos7_golang-1.12'
            useDep = true
            break
    }

    run(label, image) { main(useDep) }
}
