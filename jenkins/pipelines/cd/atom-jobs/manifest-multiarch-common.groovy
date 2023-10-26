node("delivery") {
    container("delivery") {
        stage("build multi-arch") {
            withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                sh """
            printenv harborPassword | docker login -u ${harborUser} --password-stdin hub.pingcap.net
            export DOCKER_CLI_EXPERIMENTAL=enabled
            docker manifest create ${MULTI_ARCH_IMAGE}  -a ${AMD64_IMAGE} -a ${ARM64_IMAGE}
            """
            }
        }
    }
    println "multi arch image: ${MULTI_ARCH_IMAGE}"
}
