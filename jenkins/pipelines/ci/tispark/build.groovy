def call(ghprbActualCommit, ghprbPullId, ghprbPullTitle, ghprbPullLink, ghprbPullDescription, credentialsId, tokenCredentialId, channel, teamDomain) {
    env.GOROOT = "/usr/local/go"
    env.GOPATH = "/go"
    env.PATH = "/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin"
    env.PATH = "${env.GOROOT}/bin:/home/jenkins/bin:/bin:${env.PATH}"
    env.SPARK_HOME = "/usr/local/spark-2.1.1-bin-hadoop2.7"

    catchError {
        def label = "${JOB_NAME}-${BUILD_NUMBER}"
        podTemplate(label: label,
            cloud: "kubernetes-ksyun",
            namespace: "jenkins-tispark",
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'java', alwaysPullImage: true,
                        image: "hub.pingcap.net/jenkins/centos7_golang-1.12_java:cached", ttyEnabled: true,
                        resourceRequestCpu: '2000m', resourceRequestMemory: '4Gi',
                        command: '/bin/sh -c', args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
        ) {
            node(label) {
                println "debug command:\nkubectl -n jenkins-tibigdata exec -ti ${NODE_NAME} bash"
                container("java") {
                    deleteDir()
                    stage('Checkout') {
                        dir("/home/jenkins/agent/git/tispark") {
                            sh """
                            archive_url=http://fileserver.pingcap.net/download/builds/pingcap/tispark/cache/tispark-m2-cache-latest.tar.gz
                            if [ ! "\$(ls -A /maven/.m2/repository)" ]; then curl -C - --retry 3 -sfL \$archive_url | tar -zx -C /maven || true; fi
                            """
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }

                            def specStr = "+refs/heads/*:refs/remotes/origin/*"
                            if (ghprbPullId != null && ghprbPullId != "") {
                                specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
                            }

                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: credentialsId, refspec: specStr, url: 'git@github.com:pingcap/tispark.git']]]
                        }
                    }

                    stage('Format') {
                        dir("go/src/github.com/pingcap/tispark") {
                            sh """
                            export LC_ALL=en_US.UTF-8
                            export LANG=en_US.UTF-8
                            export LANGUAGE=en_US.UTF-8
                            cp -R /home/jenkins/agent/git/tispark/. ./
                            git checkout -f ${ghprbActualCommit}
                            mvn mvn-scalafmt_2.12:format -Dscalafmt.skip=false
                            mvn com.coveo:fmt-maven-plugin:format
                            git diff --quiet
                            formatted="\$?"
                            if [[ "\${formatted}" -eq 1 ]]
                            then
                            echo "code format error, please run the following commands:"
                            echo "   mvn mvn-scalafmt_2.12:format -Dscalafmt.skip=false"
                            echo "   mvn com.coveo:fmt-maven-plugin:format"
                            exit 1
                            fi
                            """
                        }
                    }

                    stage('Build') {
                        dir("go/src/github.com/pingcap/tispark") {
                            sh """
                            git checkout -f ${ghprbActualCommit}
                            mvn clean package -Dmaven.test.skip=true
                            """
                        }
                    }
                }
            }
        }
        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
        "${ghprbPullLink}" + "\n" +
        "${ghprbPullDescription}" + "\n" +
        "Build Result: `${currentBuild.result}`" + "\n" +
        "Elapsed Time: `${duration} mins` " + "\n" +
        "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: channel, color: 'danger', teamDomain: teamDomain, tokenCredentialId: tokenCredentialId, message: "${slackmsg}"
        }
    }
}

return this
