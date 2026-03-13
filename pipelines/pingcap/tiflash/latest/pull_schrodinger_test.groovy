import java.util.UUID

def getParam = { String key, String defaultValue = "" ->
    def value = params.getOrDefault(key, defaultValue)
    return value == null ? defaultValue : "${value}"
}

def desc = getParam("desc", "TiFlash schrodinger test")
def branch = getParam("branch", "master")
def version = getParam("version", "latest")
def testcase = getParam("testcase", "")
def maxRunTime = getParam("maxRunTime", "120")
def notify = getParam("notify", "false")
def idleMinutes = getParam("idleMinutes", "5")
def tidb_commit_hash = getParam("tidb_commit_hash", "")
def tikv_commit_hash = getParam("tikv_commit_hash", "")
def pd_commit_hash = getParam("pd_commit_hash", "")
def tiflash_commit_hash = getParam("tiflash_commit_hash", "")
def FILE_SERVER_URL = getParam("FILE_SERVER_URL", "https://fileserver.pingcap.net")
def schrodingerTest = null

currentBuild.description = "${desc} branch=${branch} version=${version} testcase=${testcase}"

def label = "tiflash-schrodinger-test-v11"
def cloud = "kubernetes-ng"

if (idleMinutes != "5") {
    println "pod idleMinutes is not default 5 minutes\n use unique pod label to debug"
    UUID uuid = UUID.randomUUID()
    label = "${label}-${uuid}"
    println "pod lable: ${label}"
}

def run_with_pod(Closure body) {
    def cloud = "kubernetes-ng"
    def label = "${JOB_NAME}-${BUILD_NUMBER}"
    def namespace = "jenkins-tiflash-schrodinger"
    def pod_go_docker_image = 'hub.pingcap.net/jenkins/centos7_golang-1.16:latest'
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                            name: 'golang', alwaysPullImage: false,
                            image: "${pod_go_docker_image}", ttyEnabled: true,
                            resourceRequestCpu: '8000m', resourceRequestMemory: '12Gi',
                            command: '/bin/sh -c', args: 'cat',
                            envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
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

def checkout_name = branch
if (checkout_name in ["planner_refactory"]) {
    println "this is a feature branch, use tiflas-scripts master default"
    checkout_name = "master"
}

run_with_pod {
    container("golang") {
        println "pod save time: ${idleMinutes}"

        dir("src/tiflash") {
            deleteDir()

            if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                deleteDir()
            }

            checkout changelog: false, poll: false, scm: [
                    $class                           : 'GitSCM',
                    branches                         : [[name: checkout_name]],
                    doGenerateSubmoduleConfigurations: false,
                    userRemoteConfigs                : [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap-inc/tiflash-scripts.git']]
            ]

            sleep time: 5, unit: 'SECONDS'

            sh """
            sed -ir \"s/test-tiflash-Schrodinger-v11/${label}/g\" regression_test/schrodinger.groovy
            grep podTemplate -i regression_test/schrodinger.groovy
            """
            schrodingerTest = load 'regression_test/schrodinger.groovy'

            if (branch in ["planner_refactory"]) {
                println "this is a feature branch: ${branch}"
                tidb_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/master/sha1").trim()
                tikv_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/master/sha1").trim()
                pd_commit_hash = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/master/sha1").trim()
            }
        }
    }

    schrodingerTest.runSchrodingerTest4(cloud, branch, version, tidb_commit_hash, tikv_commit_hash, pd_commit_hash, tiflash_commit_hash, testcase, maxRunTime, notify, idleMinutes)
}
