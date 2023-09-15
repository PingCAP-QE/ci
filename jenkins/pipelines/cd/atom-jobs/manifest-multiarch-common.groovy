node("delivery") {
    container("delivery") {
        stage("build multi-arch") {
            withCredentials([usernamePassword(credentialsId: 'harbor-pingcap', usernameVariable: 'harborUser', passwordVariable: 'harborPassword')]) {
                sh """
            docker login -u ${harborUser} -p ${harborPassword} hub.pingcap.net
            cat <<EOF > manifest.yaml
image: ${MULTI_ARCH_IMAGE}
manifests:
-
    image: ${ARM64_IMAGE}
    platform:
    architecture: arm64
    os: linux
-
    image: ${AMD64_IMAGE}
    platform:
    architecture: amd64
    os: linux

EOF
            cat manifest.yaml
            curl -o manifest-tool ${FILE_SERVER_URL}/download/cicd/tools/manifest-tool-linux-amd64
            chmod +x manifest-tool
            ./manifest-tool push from-spec manifest.yaml
            """

            }
            archiveArtifacts artifacts: "manifest.yaml", fingerprint: true
        }

    }
    println "multi arch image: ${MULTI_ARCH_IMAGE}"
}