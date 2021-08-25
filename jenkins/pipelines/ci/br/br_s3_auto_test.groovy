// def ghprbTargetBranch = "master"
// def ghprbCommentBody = ""
// def ghprbPullId = ""
// def ghprbPullTitle = ""
// def ghprbPullLink = ""
// def ghprbPullDescription = ""

properties([
        parameters([
                string(
                        defaultValue: 'tidb-tools',
                        name: 'AWS_Bucket',
                        trim: true
                ),
                string(
                        defaultValue: 'br',
                        name: 'AWS_Bucket_Folder',
                        trim: true
                )
        ])
])
if (DUMPLING_FOLDER == null || DUMPLING_FOLDER == "") {
    DUMPLING_FOLDER='dumpling'
}

catchError {
    def test_case_names = []
    node("${GO1160_TEST_SLAVE}") {
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
                        #build br.test
                        cd ..
                        git clone https://github.com/pingcap/tidb.git
                        cd tidb/
                        make build_for_br_integration_test
                        mkdir -p ../AutoTest/bin/
                        cp ./bin/br.test ../AutoTest/bin/
                        cp ./bin/tidb-lightning.test ../AutoTest/bin/
                        cd ../AutoTest

                        """
                    }

                    withEnv(["S3_BUCKET=${AWS_Bucket}", "S3_FOLDER=${AWS_Bucket_Folder}", "DUMPLING_FOLDER=${DUMPLING_FOLDER}"]) {
                        withCredentials([string(credentialsId: 'jenkins-aws-secret-key-id', variable: 'AWS_ACCESS_KEY_ID')]){
                            withCredentials([string(credentialsId: 'jenkins-aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')]){
                                stage("S3 Test") {
                                    def cw = pwd()
                                    sh 'printenv'

                                    sh label: "Build and Compress testing binaries", script: """
                                    echo ${cw}
                                    make s3_test
                                    """
                                }
                            }
                        }
                    } //env

                    currentBuild.result = "SUCCESS"
                    stage('Summary') {
                    def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
                    println duration
                    }
            }
        }
    }

}


