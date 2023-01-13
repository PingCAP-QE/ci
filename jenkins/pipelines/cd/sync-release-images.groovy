// Uses Declarative syntax to run commands inside a container.
pipeline {
    agent none
    parameters {
        string  description: 'version tag for dockerhub, e.g v6.5.0-rocky', name: 'version', trim: true
    }
    stages {
        stage('update all images') {
            matrix {
                axes {
                    axis {
                        name 'IMAGE'
                        values "pingcap/br" , "pingcap/dm" , "pingcap/dumpling" , "pingcap/ng-monitoring" , "pingcap/pd" , "pingcap/ticdc" , "pingcap/tidb" , "pingcap/tidb-binlog" , "pingcap/tidb-lightning" , "pingcap/tidb-monitor-initializer" , "pingcap/tiflash" , "pingcap/tikv" , "pingcap/br-enterprise" , "pingcap/dm-enterprise" , "pingcap/dumpling-enterprise" , "pingcap/ng-monitoring-enterprise" , "pingcap/pd-enterprise" , "pingcap/ticdc-enterprise" , "pingcap/tidb-enterprise" , "pingcap/tidb-binlog-enterprise" , "pingcap/tidb-lightning-enterprise" , "pingcap/tidb-monitor-initializer-enterprise" , "pingcap/tiflash-enterprise" , "pingcap/tikv-enterprise"
                    }
                }

                stages {
                    stage("sync harbor images to dockerhub") {
                        steps {
                            echo "sync ${IMAGE}:${params.version} to ${IMAGE}:latest"
                            build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: "hub.pingcap.net/qa/${IMAGE}:${params.version}-pre"),
                                            string(name: 'TARGET_IMAGE', value: "${IMAGE}:${params.version}")
                                    ]
                        }
                    }
                }
            }
        }
    }
}
