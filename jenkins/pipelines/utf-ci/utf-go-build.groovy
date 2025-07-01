def main() {
    def targetName = params.SUITE.replaceAll('/', '-')
    def tag = params.TAG
    if (tag == "") {
        tag = params.BRANCH.replaceAll("/", "-")
        if (params.FORK != "PingCAP-QE") { tag = "${params.FORK}__${tag}".toLowerCase() }
    }

    stage("Checkout") {
        container("golang") { sh("chown -R 1000:1000 ./") }
        checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [[name: params.BRANCH]],
            userRemoteConfigs: [[url: "https://github.com/${params.FORK}/automated-tests.git",
                                 refspec: params.REFSPEC, credentialsId: "github-sre-bot"]],
            extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
        ])
    }
    stage("Build") {
        container("golang") {
            def projectDir = pwd()
            def siteScript = ""
            if (params.SUITE == "gcworker") {
                siteScript = "make download-tidb"
            }

            sh("""
            cd ticases/${params.SUITE}
            ${siteScript}
            go build -o ${targetName}
            #if [ -f suite.jsonnet ] && ! `grep -q 'std\\.extVar' suite.jsonnet`; then
            #    UTF_SUITE_SCHEMA="file://$projectDir/manifests/suite.schema.json" ./${targetName} info
            #fi

            if [ ${targetName} = "mysqltest" ]; then
                cat <<EOF > Dockerfile
            FROM hub.pingcap.net/qa/utf-go-base:20210413
            COPY ${targetName} /
            COPY t /t
            COPY r /r
            ENTRYPOINT ["/${targetName}"]
            EOF
                tar -zcf ${targetName}.tar.gz ${targetName} Dockerfile t r
            elif [ ${targetName} = "planner_oncall_test" ]; then
                cat <<EOF > Dockerfile
            FROM hub.pingcap.net/qa/utf-go-base:20210413
            COPY ${targetName} /
            COPY cases /cases
            ENTRYPOINT ["/${targetName}"]
            EOF
                tar -zcf ${targetName}.tar.gz ${targetName} Dockerfile cases
            elif [ -f suite.jsonnet ]; then
                cat <<EOF > Dockerfile
            FROM hub.pingcap.net/qa/utf-go-base:20210413
            COPY *.jsonnet *.libsonnet /
            COPY ${targetName} /
            ENTRYPOINT ["/${targetName}"]
            EOF
                tar -zcf ${targetName}.tar.gz ${targetName} *.jsonnet \$(find -maxdepth 1 -name '*.libsonnet') Dockerfile
            else
                cat <<EOF > Dockerfile
            FROM hub.pingcap.net/qa/utf-go-base:20210413
            COPY ${targetName} /
            ENTRYPOINT ["/${targetName}"]
            EOF
                tar -zcf ${targetName}.tar.gz ${targetName} Dockerfile
            fi
            """.stripIndent())

            archiveArtifacts(artifacts: "ticases/${params.SUITE}/${targetName}.tar.gz")

        }
    }
    stage("Image") {
        build(job: "image-build", parameters: [
            string(name: "CONTEXT_ARTIFACT", value: "$JOB_NAME:$BUILD_NUMBER:ticases/${params.SUITE}/${targetName}.tar.gz"),
            string(name: "DESTINATION", value: "hub.pingcap.net/qa/utf-go-${targetName}:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label, instanceCap: 5,
    idleMinutes: 60, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'golang', image: image, alwaysPullImage: false, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("automated-tests") { main() } } }
}

catchError {
    run('utf-go-build', 'hub.pingcap.net/jenkins/centos7_golang-1.21:latest') { main() }
}
