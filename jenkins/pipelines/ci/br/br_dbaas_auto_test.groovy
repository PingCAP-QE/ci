
catchError {
    def test_case_names = []
    node("build_go1160") {
            container("golang") {
                println "debug command:\nkubectl -n jenkins-tidb exec -ti ${NODE_NAME} bash"
                def ws = pwd()
                deleteDir()

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
                        """
                    }

                    stage("DBaaS Test") {
                    println "DBaaS Test Started"
                    sh label: "DBaaS test start", script: """
                    make dbaas_test
                    """
                    }
                    
                    currentBuild.result = "SUCCESS"
                    stage('Summary') {
                    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                    println duration
                    }
            }
        }
    }

}


