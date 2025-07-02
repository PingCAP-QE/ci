catchError {
    def label = "image-build"
    podTemplate(cloud: "kubernetes-ng", name: label, namespace: "jenkins-qa", label: label, instanceCap: 5,
    idleMinutes: 480, nodeSelector: "kubernetes.io/arch=amd64",
    containers: [
        containerTemplate(name: 'dockerd', image: 'registry-mirror.pingcap.net/library/docker:18.09.6-dind', privileged: true),
        containerTemplate(name: 'docker', image: 'registry-mirror.pingcap.net/library/docker:18.09.6', envVars: [envVar(key: 'DOCKER_HOST', value: 'tcp://localhost:2375')], ttyEnabled: true, command: 'cat'),
    ]) {
        node(label) {
            deleteDir()

            stage("Download") {
                def copy_from = params.CONTEXT_ARTIFACT.split(":")
                if (copy_from.length == 3) {
                    copyArtifacts(projectName: copy_from[0], selector: [$class: 'SpecificBuildSelector', buildNumber: copy_from[1]])
                    sh("tar -xf ${copy_from[2]}")
                } else if (params.CONTEXT_URL.endsWith(".tar.gz") || params.CONTEXT_URL.endsWith(".tgz")) {
                    sh("wget -O- '${params.CONTEXT_URL}' | tar xz")
                } else if (params.CONTEXT_URL) {
                    sh("wget -O ${params.DOCKERFILE_PATH} '${params.CONTEXT_URL}'")
                }
                if (params.DOCKERFILE_CONTENT) {
                    writeFile(file: params.DOCKERFILE_PATH, text: params.DOCKERFILE_CONTENT)
                }
            }

            container("docker") {
                stage("Build") {
                    def dst = params.DESTINATION
                    if (!dst) {
                        dst = sh(script: "tail -n 1 ${params.DOCKERFILE_PATH} | grep -o -e 'hub.pingcap.net.\\+\$'", returnStdout: true).trim()
                    }
                    if (!dst) {
                        error("DESTINATION is missing!")
                    }
                    docker.withRegistry("https://hub.pingcap.net", "harbor-qa") {
                        sh("docker build -f ${params.DOCKERFILE_PATH} -t ${dst} .")
                        sh("docker push ${dst}")
                    }
                    echo("${dst}")
                }
            }

        }
    }
}
