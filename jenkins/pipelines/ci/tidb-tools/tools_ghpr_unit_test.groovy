
GO_VERSION = "go1.20"
POD_GO_IMAGE = "hub.pingcap.net/jenkins/centos7_golang-1.20:latest"
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
    "go1.20": "hub.pingcap.net/jenkins/centos7_golang-1.20:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-${BUILD_NUMBER}",
    "go1.20": "${JOB_NAME}-go1200-${BUILD_NUMBER}",
]

node("master") {
    deleteDir()
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/goversion-select-lib-upgrade-temporary.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes"
    def namespace = "jenkins-tidb"
    def jnlp_docker_image = "jenkins/inbound-agent:4.3-4"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
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
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} bash"
            body()
        }
    }
}


catchError {
    stage('Prepare') {
        run_with_pod {
            def ws = pwd()
            container("golang") {
                deleteDir()
                dir("/home/jenkins/agent/git/tidb-tools") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-tools.git']]]
                    } catch (error) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 60
                            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-tools.git']]]
                        }
                    }
                }

                // tidb-tools
                dir("go/src/github.com/pingcap/tidb-tools") {
                    sh """
                        cp -R /home/jenkins/agent/git/tidb-tools/. ./
                        git checkout -f ${ghprbActualCommit}
                        GOPATH=\$GOPATH:${ws}/go make build
                    """
                }

                stash includes: "go/src/github.com/pingcap/tidb-tools/**", name: "tidb-tools"
            }
        }
    }

    stage('Test') {
        def tests = [:]

        tests["Unit Test"] = {
            def label = "test-${UUID.randomUUID().toString()}"
            podTemplate(label: label, containers: [
                    containerTemplate(name: 'golang',alwaysPullImage: false,
                            image: "${POD_GO_IMAGE}",
                            ttyEnabled: true, command: 'cat'),
                    containerTemplate(
                            name: 'mysql',
                            image: 'hub.pingcap.net/jenkins/mysql:5.7',
                            ttyEnabled: true,
                            alwaysPullImage: false,
                            envVars: [
                                    envVar(key: 'MYSQL_ALLOW_EMPTY_PASSWORD', value: '1'),
                            ],
                            args: '--log-bin --binlog-format=ROW --server-id=1',
                    )
            ]) {
                node(label) {
                    container("golang") {
                        def ws = pwd()
                        deleteDir()
                        unstash "tidb-tools"
                        dir("go/src/github.com/pingcap/tidb-tools") {
                            sh """
                            for i in {1..10} mysqladmin ping -h0.0.0.0 -P 3306 -uroot --silent; do if [ \$? -eq 0 ]; then break; else if [ \$i -eq 10 ]; then exit 2; fi; sleep 1; fi; done
                            export MYSQL_HOST=127.0.0.1
                            export MYSQL_PORT=3306
                            GOPATH=\$GOPATH:${ws}/go make test
                            """
                        }
                    }
                }
            }
        }


        parallel tests
    }

    currentBuild.result = "SUCCESS"
}

