node('build') {
    container("java") {
        def ws = pwd()
        deleteDir()

        dir("/home/jenkins/agent/git/tispark/") {
            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                deleteDir()
            }
            sh """
            cd ~
            pwd
            rm -rf ./tispark-test
            git clone https://github.com/pingcap/tispark-test
            yes | cp -rf ./tispark-test/tispark-regression-test-daily/settings.xml /maven/.m2/
            cd ~
            """
            archive="tispark-m2-cache-latest.tar.gz"
            sh "rm -rf /maven/.m2/repository/*"

            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tispark.git']]]
            sh "mvn clean package -Dmaven.test.skip=true"

            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'release-2.4']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tispark.git']]]
            sh "mvn clean package -Dmaven.test.skip=true -Pscala-2.12"

            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'release-2.5']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tispark.git']]]
            sh "mvn clean package -Dmaven.test.skip=true"

            sh """
            ls -all /maven/.m2/repository
            tar -zcf $archive -C  /maven .m2
            ls -all
            curl -F builds/pingcap/tispark/cache/$archive=@$archive fileserver.pingcap.net/upload
            echo "http://fileserver.pingcap.net/download/builds/pingcap/tispark/cache/$archive"
            """
        }
    }
}
