node{
    def CREDENTIALS_ID = "github-sre-bot-ssh"
    def CODECOV_CREDENTIALS_ID = "tp-codecov-token"
    def BUILD_URL = "git@github.com:pingcap/tidb-operator.git"
    def BUILD_BRANCH = "${GIT_REF}"
    def RELEASE_TAG = "${RELEASE_VER}"
    def rootDir = pwd()
    def jenkinsfile = "${rootDir}/ci/release-nightly.groovy"

    env.ghprbPullId = "release-${BUILD_BRANCH}-nightly"
    env.ghprbPullTitle = "build tag refs/tags/${BUILD_BRANCH}"
    env.ghprbPullLink = "https://github.com/pingcap/tidb-operator/tree/${BUILD_BRANCH}"
    env.ghprbPullDescription = "build tag refs/tags/${BUILD_BRANCH} and upload artifacts"

    println("Current jenkinsfile: " + jenkinsfile)
    println("build branch: " + BUILD_BRANCH)
    println("release tag: " + RELEASE_TAG)
    println("ghprbPullId: " + ghprbPullId)
    println("ghprbPullTitle: " + ghprbPullTitle)
    println("ghprbPullLink: " + ghprbPullLink)
    println("ghprbPullDescription: "+ ghprbPullDescription)

    checkout changelog: false,
    poll: false,
    scm: [
      $class: 'GitSCM',
      branches: [[name: "${BUILD_BRANCH}"]],
      userRemoteConfigs: [[
        credentialsId: "${CREDENTIALS_ID}",
        refspec: '+refs/heads/*:refs/remotes/origin/* +refs/pull/*:refs/remotes/origin/pr/*',
        url: "${BUILD_URL}",
        ]]  
    ]

    def operatorE2e = load "${jenkinsfile}"

    build job: 'tidb-operator-release-publish', parameters: [
        string(name: 'RELEASE_TAG', value: RELEASE_TAG),
        string(name: 'BUILD_BRANCH', value: BUILD_BRANCH),
                                                            ], wait: false
    build job: 'tidb-operator-release-publish-cli', parameters: [
        string(name: 'RELEASE_TAG', value: RELEASE_TAG),
        string(name: 'BUILD_BRANCH', value: BUILD_BRANCH),
                                                            ], wait: false
}