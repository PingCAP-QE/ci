properties([
        parameters([
                string(name: 'FORK', defaultValue: 'tikv', description: '', trim: true),
                string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
                string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
                booleanParam(name: 'TIUP_PUBLISH', defaultValue: false, description: ''),
                string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*', description: '', trim: true),
                string(name: 'MAKE_TARGET', defaultValue: 'dist_release', description: '', trim: true),
                string(name: 'VERSION', defaultValue: '', description: '', trim: true),
        ])
])

def main() {
    def archiveURL = ""
    def tag = params.BRANCH.replaceAll("/", "-")
    if (params.FORK != "tikv") {
        tag = "${tag}-${params.FORK}"
    }
    if (params.MAKE_TARGET.contains("fail_")) {
        tag = "${tag}-failpoint"
    }
    if (params.VERSION != "") {
        tag = "$params.VERSION"
    }

    def release = 1
    if (params.FORK == "tikv" && params.MAKE_TARGET != "fail_release") {
        def filepath = "http://fileserver.pingcap.net/download/builds/pingcap/tikv/optimization/${params.BRANCH}/centos7/tikv-server.tar.gz"
        release = sh(returnStatus: true, script: """
                    curl --output /dev/null --silent --head --fail ${filepath}
                    """)
        if (release == 0) {
            sh "curl ${filepath}| tar xz"
        }
    }
    if (release != 0) {
        stage("Checkout") {
            checkout(changelog: false, poll: false, scm: [
                    $class           : "GitSCM",
                    branches         : [[name: params.BRANCH]],
                    userRemoteConfigs: [[url: "https://github.com/${params.FORK}/tikv.git", refspec: params.REFSPEC]],
                    extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
            ])
        }
    }
    stage("Build") {
        container("rust") {
            retry(3) {
                sh("""
                cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc

COPY entrypoint.sh /entrypoint.sh
COPY tikv-ctl /bin
COPY tikv-server /bin

RUN ln -sf /bin/tikv-server /tikv-server

ENTRYPOINT ["/entrypoint.sh"]

# hub.pingcap.net/qa/tikv
__EOF__
                curl -sL -o tici.sh http://fileserver.pingcap.net/download/pingcap/qa/scripts/tici.sh
                chmod +x tici.sh
                curl -sL http://fileserver.pingcap.net/download/pingcap/qa/dockerfiles/common/entrypoint.sh | sed 's/__BIN__/tikv-server/g' > entrypoint.sh && chmod +x entrypoint.sh
            """)
            }
            if (release != 0) {
                sh("""
                grpcio_ver=`grep -A 1 'name = "grpcio"' Cargo.lock | tail -n 1 | cut -d '"' -f 2`
                if [[ ! "0.8.0" > "\$grpcio_ver" ]]; then
                    echo using gcc 8
                    source /opt/rh/devtoolset-8/enable
                fi
                ROCKSDB_SYS_STATIC=1 make ${params.MAKE_TARGET}
                """)
            }

            retry(3) {
                archiveURL = sh(script: "./tici.sh publish $params.TIUP_PUBLISH $params.TIUP_MIRRORS tikv-server pingcap/qa/archives tikv ${tag} Dockerfile entrypoint.sh -C bin .", returnStdout: true).trim()
            }
            echo(archiveURL)
        }
    }
    stage("Image") {
        build(job: "image-build", parameters: [
                string(name: "CONTEXT_URL", value: archiveURL),
                string(name: "DESTINATION", value: "hub.pingcap.net/qa/tikv:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(name: label, label: label, instanceCap: 5, idleMinutes: 60, containers: [
            containerTemplate(name: 'rust', image: image, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("tikv") { main() } } }
}

catchError {
    run('build-tikv', 'hub.pingcap.net/jenkins/centos7_golang-1.13_rust') { main() }
}
