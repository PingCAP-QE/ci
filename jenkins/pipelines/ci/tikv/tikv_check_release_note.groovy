catchError {
    stage("check release note") {
            //sh "echo $ghprbPullLongDescription | egrep 'Release note'"
            //sh "python -v"
        node('build_go1130') {
            //def goVersion = new Utils(this).detectGoVersion("https://raw.githubusercontent.com/pingcap/tidb/master/circle.yml")
            //buildSlave = GO_BUILD_SLAVE
            //testSlave = GO_TEST_SLAVE
            //sh "echo $ghprbPullLongDescription"
            println "description $ghprbPullLongDescription"
            sh """
            mkdir -p $ghprbActualCommit
            rm -rf $ghprbActualCommit/description.txt
            cat <<"EOT" >> $ghprbActualCommit/description.txt
$ghprbPullLongDescription
EOT"""
            //echo "$ghprbPullLongDescription" > a.out
            //sh "echo \"$ghprbPullLongDescription\" > $ghprbActualCommit"
            sh "egrep 'Release note.*\\\\r\\\\n[-|\\*].+' $ghprbActualCommit/description.txt || ( echo 'No release note, Please follow https://github.com/pingcap/community/blob/master/contributors/release-note-checker.md' && exit 1) "

            //echo "GO: $goVersion BUILD: $buildSlave TEST: $testSlave"
        }
        
    }
    currentBuild.result = "SUCCESS"
}
stage("summary") {
    if (currentBuild.result != "SUCCESS") {
        node("master") {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                sh """
                    rm -f comment-pr
                    curl -O http://fileserver.pingcap.net/download/comment-pr
                    chmod +x comment-pr
                    ./comment-pr --token=$TOKEN --owner=tikv --repo=tikv --number=${ghprbPullId} --comment="No release note, Please follow https://github.com/pingcap/community/blob/master/contributors/release-note-checker.md"
                """
            }
        }
    }
}