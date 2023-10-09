pipeline {
    agent none
    parameters {
        string defaultValue: 'v1', description: '', name: 'version', trim: true
    }
    stages {
        stage('update base images') {
            matrix {
                axes {
                    axis {
                        name 'IMAGE'
                        values 'pingcap-base', 'tidb-base', 'tikv-base', 'tiflash-base', 'pd-base', 'tools-base'
                    }
                }

                stages {
                    stage("update tag") {
                        steps {
                            echo "sync hub.pingcap.net/bases/${IMAGE}:${params.version} to pingcap/${IMAGE}:${params.version}"
                            build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/bases/${IMAGE}:${params.version}"),
                                            string(name: 'TARGET_IMAGE', value: "pingcap/${IMAGE}:${params.version}")
                                    ]
                        }
                    }
                }
            }
        }
    }
}
