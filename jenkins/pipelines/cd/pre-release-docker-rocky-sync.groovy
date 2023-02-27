import java.text.SimpleDateFormat
pipeline {
    agent none
    parameters {
        string  description: 'RC release tag', name: 'Version', trim: true
    }
    stages {
        stage('prepare'){
            steps{
                script{
                    def date = new Date()
                    ts13 = date.getTime() / 1000
                    ts10 = (Long) ts13
                    sdf = new SimpleDateFormat("yyyyMMdd")
                    day = sdf.format(date)
                }
            }
        }
        stage('sync-failpoint') {
            matrix {
                axes {
                    axis {
                        name 'prod'
                        values  "pd", "tidb", "tikv"
                    }
                }
                stages {
                    stage("sync") {
                        steps {
                            script{
                                def source = "hub.pingcap.net/qa/${prod}:${params.Version}-rocky-pre-failpoint-amd64"
                                def target = "hub.pingcap.net/qa/${prod}:${params.Version}-pre-failpoint"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                            }
                        }
                    }
                }
            }
        }
        stage('sync-multiarch') {
            matrix {
                axes {
                    axis {
                        name 'prod'
                        values "br", "dm", "dumpling", "ng-monitoring", "pd", "ticdc", "tidb", "tidb-binlog", "tidb-lightning", "tidb-monitor-initializer", "tiflash", "tikv"
                    }
                }
                stages {
                    stage("sync") {
                        steps {
                            script{
                                def source = "hub.pingcap.net/qa/${prod}:${params.Version}-rocky-pre"
                                def target = "hub.pingcap.net/qa/${prod}:${params.Version}-pre"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                                source = "hub.pingcap.net/qa/${prod}-enterprise:${params.Version}-rocky-pre"
                                target = "hub.pingcap.net/qa/${prod}-enterprise:${params.Version}-pre"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                            }
                            script {
                                def source = "hub.pingcap.net/qa/${prod}:${params.Version}-rocky-pre"
                                def target = "pingcap/${prod}:${params.Version}-pre"
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                                source = "hub.pingcap.net/qa/${prod}-enterprise:${params.Version}-rocky-pre"
                                target = "gcr.io/pingcap-public/dbaas/${prod}:${params.Version}-${day}-${ts10}"
                                if (prod == "tidb-monitor-initializer"){
                                    target = "gcr.io/pingcap-public/dbaas/${prod}:${params.Version}"
                                }
                                echo "sync $source to $target"
                                build job: 'jenkins-image-syncer',
                                    parameters: [
                                            string(name: 'SOURCE_IMAGE', value: source),
                                            string(name: 'TARGET_IMAGE', value: target)
                                    ]
                            }
                        }
                    }
                }
            }
        }
    }
}
