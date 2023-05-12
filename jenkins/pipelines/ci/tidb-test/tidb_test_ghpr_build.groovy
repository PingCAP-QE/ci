def slackcolor = 'good'
def githash

def TIDB_BRANCH = ({
    def m = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m) {
        return "${m.group(1)}"
    }
    return "master"
}).call()

def downRef = { name ->
    def m = name.trim() =~ /^pr\/(\d+)$/
    if (m) {
        return "pull/${m[0][1]}/head"
    }
    return name
}
def downUrl = "https://api.github.com/repos/pingcap/tidb/tarball/${downRef(TIDB_BRANCH)}"
println "TIDB_BRANCH=${TIDB_BRANCH} DOWNLOAD_URL=${downUrl}"

GO_VERSION = "go1.20"
POD_GO_IMAGE = ""
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
    def goversion_lib_url = 'https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/pipelines/ci/tidb/goversion-select-lib.groovy'
    sh "curl --retry 3 --retry-delay 5 --retry-connrefused --fail -o goversion-select-lib.groovy  ${goversion_lib_url}"
    def goversion_lib = load('goversion-select-lib.groovy')
    GO_VERSION = goversion_lib.selectGoVersion(ghprbTargetBranch)
    POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
    println "go version: ${GO_VERSION}"
    println "go image: ${POD_GO_IMAGE}"
}


def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-tidb-test"
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

try {
    run_with_pod {
        def ws = pwd()

        stage("Checkout") {
            container("golang") {
                // update cache
                parallel 'tidb-test': {
                    dir("go/src/github.com/pingcap/tidb-test") {
                        def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tidb-test.tar.gz"
                        def cacheExisted = sh(returnStatus: true, script: """
                            if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                            """)
                        if (cacheExisted == 0) {
                            println "get code from fileserver to reduce clone time"
                            println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                            sh """
                            curl -C - --retry 3 -f -O ${codeCacheInFileserverUrl}
                            tar -xzf src-tidb-test.tar.gz --strip-components=1
                            rm -f src-tidb-test.tar.gz
                            """
                        } else {
                            println "get code from github"
                        }
                        checkout(changelog: false, poll: false, scm: [
                            $class: "GitSCM",
                            branches: [
                                [name: "${ghprbActualCommit}"],
                            ],
                            userRemoteConfigs: [
                                [
                                    url: "git@github.com:pingcap/tidb-test.git",
                                    refspec: "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*",
                                    credentialsId: 'github-sre-bot-ssh',
                                ]
                            ],
                            extensions: [
                                [$class: 'PruneStaleBranch'],
                                [$class: 'CleanBeforeCheckout'],
                            ],
                        ])
                    }
                }, 'tidb': {
                    dir("go/src/github.com/pingcap/tidb") {
                        deleteDir()
                        sh("wget -O- ${downUrl} | tar xz --strip=1")
                    }
                }
            }
        }

        stage("Build") {
            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    timeout(10) {
                        sh """
                        TIDB_SRC_PATH=${ws}/go/src/github.com/pingcap/tidb make check
                        """
                    }
                    for (binCase in ['partition_test', 'coprocessor_test', 'concurrent-sql']) {
                        if (fileExists("${binCase}/build.sh")) { dir(binCase) { sh "bash build.sh" } }
                    }
                }
            }
        }

        stage("Upload") {
            def filepath = "builds/pingcap/tidb-test/pr/${ghprbActualCommit}/centos7/tidb-test.tar.gz"
            def refspath = "refs/pingcap/tidb-test/pr/${ghprbPullId}/sha1"

            dir("go/src/github.com/pingcap/tidb-test") {
                container("golang") {
                    timeout(10) {
                        sh """
                        rm -rf .git
                        tar czvf tidb-test.tar.gz ./*
                        curl -f -F ${filepath}=@tidb-test.tar.gz ${FILE_SERVER_URL}/upload
                        echo "pr/${ghprbActualCommit}" > sha1
                        curl -f -F ${refspath}=@sha1 ${FILE_SERVER_URL}/upload
                        """
                    }
                }
            }
        }
    }
    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    slackcolor = 'danger'
    echo "${e}"
}
