node("delivery") {
    container("delivery") {
        stage("build multi-arch") {
            if (params.MULTI_ARCH_IMAGE.startsWith("us-docker.pkg.dev/pingcap-testing-account/")) {
                docker.withRegistry("https://us-docker.pkg.dev", "pingcap-testing-account") {
                    sh """
                        export DOCKER_CLI_EXPERIMENTAL=enabled
                        docker manifest create ${MULTI_ARCH_IMAGE}  -a ${AMD64_IMAGE} -a ${ARM64_IMAGE}
                        docker manifest push ${MULTI_ARCH_IMAGE}
                    """
                }
            } else {
                docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                    sh """
                        export DOCKER_CLI_EXPERIMENTAL=enabled
                        docker manifest create ${MULTI_ARCH_IMAGE}  -a ${AMD64_IMAGE} -a ${ARM64_IMAGE}
                        docker manifest push ${MULTI_ARCH_IMAGE}
                    """
                }
            }
            println "multi arch image: ${MULTI_ARCH_IMAGE}"
        }
    }
}
