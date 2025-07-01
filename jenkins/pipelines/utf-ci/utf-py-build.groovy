def main() {
    def tag = params.TAG
    if (tag == "") {
        tag = params.BRANCH.replaceAll("/", "-")
        if (params.FORK != "PingCAP-QE") { tag = "${params.FORK}__${tag}".toLowerCase() }
    }

    stage("Checkout") {
        container("python") { sh("chown -R 1000:1000 ./")}
        checkout(changelog: false, poll: false, scm: [
            $class           : "GitSCM",
            branches         : [[name: params.BRANCH]],
            userRemoteConfigs: [[url: "https://github.com/${params.FORK}/automated-tests.git",
                                 refspec: params.REFSPEC, credentialsId: "github-sre-bot"]],
            extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
        ])
    }

    stage("Test") {
        container("python") {
            sh("""
            cd framework
            pip install tox
            make test
            """)
        }
    }

    stage("Package") {
        container("python") {
            def projectDir = pwd()
            sh("""
            cat <<EOF > Dockerfile
            FROM hub.pingcap.net/qa/utf-py-base:20210225
            WORKDIR /automated-tests
            COPY requirements.txt ./
            RUN pip install -r requirements.txt
            COPY framework ./framework
            RUN pip install ./framework
            COPY cases ./cases
            EOF
            tar -zcf utf-python.tar.gz cases/ framework/ requirements.txt Dockerfile
            """.stripIndent())

            archiveArtifacts(artifacts: "utf-python.tar.gz")
        }
    }

    stage("Image") {
        build(job: "image-build", parameters: [
            string(name: "CONTEXT_ARTIFACT", value: "$JOB_NAME:$BUILD_NUMBER:utf-python.tar.gz"),
            string(name: "DESTINATION", value: "hub.pingcap.net/qa/utf-python:${tag}"),
        ])
    }
}

def run(label, image, Closure main) {
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label,
    instanceCap: 5, idleMinutes: 60, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'python', image: image, alwaysPullImage: false, ttyEnabled: true, command: 'cat'),
    ]) { node(label) { dir("automated-tests") { main() } } }
}

catchError {
    run('utf-py-build', 'registry-mirror.pingcap.net/library/python:3.8') { main() }
}
