
access_token = ""
cloud_tier = "aws"
catchError {
     podTemplate(name: "build_go1160-${BUILD_NUMBER}", label: "build_go1160-${BUILD_NUMBER}", instanceCap: 10, nodeSelector: "kubernetes.io/arch=amd64", containers: [
        containerTemplate(name: 'golang', image: 'registry-mirror.pingcap.net/library/golang:1.16', alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'selenium', image: 'elgalu/selenium', alwaysPullImage: true, ttyEnabled: true, command: 'cat'),
    ]) {
        node("build_go1160-${BUILD_NUMBER}") {
                stage("Checkout") {
                    container("selenium") { sh("chown -R 1000:1000 ./") }

                    checkout(changelog: false, poll: false, scm: [
                        $class           : "GitSCM",
                        branches         : [[name: "*/main"]],
                        userRemoteConfigs: [[url: "https://github.com/fengou1/AutoTest.git",
                                            credentialsId: "FengOu"]],
                        extensions       : [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']],
                    ])
                }

                stage("GetToken") {
                        container("selenium") {
                            def projectDir = pwd()
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE'){
                                sh("cd ${projectDir}/dbaas/token")
                                withCredentials([string(credentialsId: 'dataplatform_dbaas', variable: 'dbaas_token_credentials')]) {
                                    sh("python ${projectDir}/dbaas/token/get_auth0_token.py --url=https://staging.tidbcloud.com/")
                                }
                                //acount_token = sh(returnStdout: true, script: 'cat /home/jenkins/agent/tokens/*')
                                access_token = readFile('/home/jenkins/agent/tokens/feng.ou@pingcap.com')
                             //println "token is ${access_token}"
                            }
                        }

                 }
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
                    def ws = pwd()
                    deleteDir()
                    println "token is ${access_token}"
                    // Checkout and build testing binaries.
                    dir("${ws}go/src/github.com/AutoTest") {
                        stage('Prepare') {
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }

                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'main']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'FengOu', refspec: '+refs/heads/*:refs/remotes/origin/*', url: 'https://github.com/fengou1/AutoTest.git']]]

                            sh label: "Build testing binaries", script: """
                            echo 'begin to build'
                            go version
                            make dbaas
                            make build_report
                            """
                        }

                        stage("DBaaS AWS Test") {
                            withEnv(["access_token=${access_token}", "cloud_tier=${cloud_tier}"]) {
                                println "DBaaS Test Started"
                                sh label: "DBaaS test start", script: """
                                make dbaas_test
                            """
                            }
                        }

                        stage("DBaaS GCP Test") {
                        cloud_tier = "gcp"
                            withEnv(["access_token=${access_token}", "cloud_tier=${cloud_tier}"]) {
                                println "DBaaS Test Started"
                                sh label: "DBaaS test start", script: """
                                make dbaas_test
                            """
                            }
                        }
                        
                        currentBuild.result = "SUCCESS"
                        stage('Report') {
                            def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                            println duration
                            println "Test Result Reported to Feishu"
                                sh label: "Feishu Test Report", script: """
                                make report
                            """
                        }
                }
                
            }
        }
    }


}