catchError {
    stage("check title note") {
            //sh "echo $ghprbPullLongDescription | egrep 'Release note'"
            //sh "python -v"
        node('build_go1130') {
            //def goVersion = new Utils(this).detectGoVersion("https://raw.githubusercontent.com/pingcap/tidb/master/circle.yml")
            //buildSlave = GO_BUILD_SLAVE
            //testSlave = GO_TEST_SLAVE
            //sh "echo $ghprbPullLongDescription"
            println "title $ghprbPullTitle"
            sh """
            mkdir -p $ghprbActualCommit
            rm -rf $ghprbActualCommit/title.txt
            cat <<"EOT" >> $ghprbActualCommit/title.txt
$ghprbPullTitle
EOT"""
            //echo "$ghprbPullLongDescription" > a.out
            //sh "echo \"$ghprbPullLongDescription\" > $ghprbActualCommit"
            sh "egrep '.+: .+' $ghprbActualCommit/title.txt || ( echo 'Please format title' && exit 1) "

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
                    # ./comment-pr --token=$TOKEN --owner=pingcap --repo=tidb --number=${ghprbPullId} --comment="Please format title"
                    ./comment-pr --token=$TOKEN --owner=pingcap --repo=tidb --number=${ghprbPullId} --comment="Please follow PR Title Format: \r\n - pkg [, pkg2, pkg3]: what's changed\r\n\r\nOr if the count of mainly changed packages are more than 3, use\r\n
 - *: what's changed"
                """
            }
        }
    }
}
