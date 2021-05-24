
def BUILD_URL = 'git@github.com:pingcap/tidb.git'
def ENTERPRISE_BUILD_URL = 'git@github.com:pingcap/enterprise-plugin.git'
def task = "build-image"
catchError {
    node("build_go1130") {
        def ws = pwd()
        container("golang") {
            stage("Checkout") {
                dir("go/src/github.com/pingcap/tidb") {
                        git credentialsId: 'github-sre-bot-ssh', url: "${BUILD_URL}", branch: "master"
                        githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
                dir("go/src/github.com/pingcap/enterprise-plugin") {
                        git credentialsId: 'github-sre-bot-ssh', url: "${ENTERPRISE_BUILD_URL}", branch: "master"
                        githash = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
                }
            }
            stage("Build") {
                dir("go/src/github.com/pingcap/tidb") {
                    timeout(20) {
                        sh """
                        mkdir -p \$GOPATH/pkg/mod && mkdir -p ${ws}/go/pkg && ln -sf \$GOPATH/pkg/mod ${ws}/go/pkg/mod
                        GOPATH=${ws}/go make
                        cd cmd/pluginpkg
                        go build
                        """
                    }
                }
                dir("go/src/github.com/pingcap/enterprise-plugin/whitelist") {
                    sh """
                    GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist
                    """
                }
                dir("go/src/github.com/pingcap/enterprise-plugin/audit") {
                    sh """
                    GOPATH=${ws}/go ${ws}/go/src/github.com/pingcap/tidb/cmd/pluginpkg/pluginpkg -pkg-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit -out-dir ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit
                    """
                }
                stash includes: "go/src/github.com/pingcap/**", name: "pingcap"
            }
        }
    }
    podTemplate(name: task, label: task, instanceCap: 5, idleMinutes: 120, containers: [
        containerTemplate(name: 'dockerd', image: 'docker:18.09.6-dind', privileged: true),
        containerTemplate(name: 'docker', image: 'docker:18.09.6', envVars: [
                envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375'),
        ], ttyEnabled: true, command: 'cat'),
    ]) {
        node(task) {
            container("docker") {
                def ws = pwd()
                stage('Prepare') {
                    unstash "pingcap"
                }

                stage('Push tidb-enterprise Docker') {
                    dir('tidb_enterprise_docker_build') {
                        sh """
                    cp -f ${ws}/go/src/github.com/pingcap/tidb/bin/tidb-server ./tidb-server
                    cp -f ${ws}/go/src/github.com/pingcap/enterprise-plugin/whitelist/whitelist-1.so ./whitelist-1.so
                    cp -f ${ws}/go/src/github.com/pingcap/enterprise-plugin/audit/audit-1.so ./audit-1.so
                    cat > Dockerfile << __EOF__
FROM registry-mirror.pingcap.net/pingcap/alpine-glibc
COPY tidb-server /tidb-server
RUN mkdir -p /plugins
COPY whitelist-1.so /plugins/whitelist-1.so
COPY audit-1.so /plugins/audit-1.so
EXPOSE 4000
ENTRYPOINT ["/tidb-server"]
__EOF__
                """
                    }
                    docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                        sh """
                        cd tidb_enterprise_docker_build
                        docker build --network=host -t hub.pingcap.net/pingcap/tidb-enterprise:latest .
                        docker push hub.pingcap.net/pingcap/tidb-enterprise:latest
                        """
                    }
                }
            }
        }

    currentBuild.result = "SUCCESS"
    }
}
