// properties([
//     parameters([
//         string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
//         string(name: 'REFSPEC', defaultValue: '+refs/heads/*:refs/remotes/origin/*', description: '', trim: true),
//         string(name: 'TAG', defaultValue: 'latest', description: '', trim: true)
//     ])
// ])

catchError {

    def label = 'build-tikv-client-c-builder-image'

    podTemplate(name: label, label: label, instanceCap: 5,
                containers: [
                    containerTemplate(name: 'kaniko', image: 'hub.pingcap.net/zyguan/kaniko', ttyEnabled: true, command: 'cat'),
                ]) {
        node(label) {
            dir("client-c") {
                stage("checkout") {
                    checkout(changelog: false, poll: false, scm: [
                             $class: "GitSCM",
                             branches: [ [ name: params.BRANCH ] ],
                             userRemoteConfigs: [ [ url: "https://github.com/tikv/client-c.git", refspec: params.REFSPEC ] ],
                             extensions: [ [$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout'] ],
                    ])
                }
                container("kaniko") {
                    stage("build image") {
                        withCredentials([file(credentialsId: 'pingcap-docker-config', variable: 'DOCKER_CONFIG')]) {
                            sh """
                            mkdir -p \$HOME/.docker && cp $DOCKER_CONFIG \$HOME/.docker/config.json
                            cd ci && executor --context dir://\$(pwd) --destination hub.pingcap.net/pingcap/tikv-client-c-builder:${params.TAG}
                            """
                        }
                        echo("hub.pingcap.net/pingcap/tikv-client-c-builder:${params.TAG}")
                    }
                }
            }
        }
    }
}
