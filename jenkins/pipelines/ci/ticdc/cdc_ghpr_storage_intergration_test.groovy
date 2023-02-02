echo "release test: ${params.containsKey("release_test")}"

if (params.containsKey("release_test")) {
    ghprbActualCommit = params.release_test__cdc_commit
    ghprbTargetBranch = params.release_test__release_branch
    ghprbPullId = ""
    ghprbCommentBody = params.release_test__comment_body
    ghprbPullLink = "release-test"
    ghprbPullTitle = "release-test"
    ghprbPullDescription = "release-test"
}

if (params.containsKey("triggered_by_upstream_pr_ci")) {
    // Triggered by upstream (TiDB/TiKV/PD) PR.
    // It contains keys:
    //     booleanParam(name: 'force', value: true),
    //     string(name: 'upstream_pr_ci', value: "tikv"),
    //     string(name: 'upstream_pr_ci_ghpr_target_branch', ghprbTargetBranch),
    //     string(name: 'upstream_pr_ci_ghpr_actual_commit', ghprbActualCommit),
    //     string(name: 'upstream_pr_ci_ghpr_pull_id', ghprbPullId),
    //     string(name: 'upstream_pr_ci_ghpr_pull_title', ghprbPullTitle),
    //     string(name: 'upstream_pr_ci_ghpr_pull_link', ghprbPullLink),
    //     string(name: 'upstream_pr_ci_ghpr_pull_description', ghprbPullDescription),
    //     string(name: 'upstream_pr_ci_override_tidb_download_link', tidb_url),
    //     string(name: 'upstream_pr_ci_override_tikv_download_link', tikv_url),
    //     string(name: 'upstream_pr_ci_override_pd_download_link', pd_url),
    echo "upstream pr test: ${params.containsKey("triggered_by_upstream_pr_ci")}"
    ghprbTargetBranch = params.upstream_pr_ci_ghpr_target_branch
    ghprbActualCommit = params.upstream_pr_ci_ghpr_actual_commit
    ghprbCommentBody = ""
    ghprbPullId = params.getOrDefault("upstream_pr_ci_ghpr_pull_id", "")
    ghprbPullTitle = params.getOrDefault("upstream_pr_ci_ghpr_pull_title", "")
    ghprbPullLink = params.getOrDefault("upstream_pr_ci_ghpr_pull_link", "")
    ghprbPullDescription = params.getOrDefault("upstream_pr_ci_ghpr_pull_description", "")
}

def ciRepoUrl = "https://github.com/PingCAP-QE/ci.git"
def ciRepoBranch = "main"

def specStr = "+refs/pull/${ghprbPullId}/*:refs/remotes/origin/pr/${ghprbPullId}/*"
if (ghprbPullId == null || ghprbPullId == "") {
    specStr = "+refs/heads/*:refs/remotes/origin/*"
}

GO_VERSION = "go1.19"
POD_GO_IMAGE = ""
GO_IMAGE_MAP = [
    "go1.13": "hub.pingcap.net/jenkins/centos7_golang-1.13:latest",
    "go1.16": "hub.pingcap.net/jenkins/centos7_golang-1.16:latest",
    "go1.18": "hub.pingcap.net/jenkins/centos7_golang-1.18.5:latest",
    "go1.19": "hub.pingcap.net/jenkins/centos7_golang-1.19:latest",
]
POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-build-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-build-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-build-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-build-${BUILD_NUMBER}",
]
TEST_POD_LABEL_MAP = [
    "go1.13": "${JOB_NAME}-go1130-test-${BUILD_NUMBER}",
    "go1.16": "${JOB_NAME}-go1160-test-${BUILD_NUMBER}",
    "go1.18": "${JOB_NAME}-go1180-test-${BUILD_NUMBER}",
    "go1.19": "${JOB_NAME}-go1190-test-${BUILD_NUMBER}",
]

feature_branch_use_go13 = []
feature_branch_use_go16 = ["hz-poc", "ft-data-inconsistency", "br-stream"]
feature_branch_use_go18 = ["release-multi-source", "fb/latency"]
feature_branch_use_go19 = []

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

// Version Selector
// branch or tag
// == branch
//  master use go1.19
//  release branch >= release-6.3 use go1.19
//  release branch >= release-6.0 use go1.18
//  release branch >= release-5.1 use go1.16
//  release branch < release-5.0 use go1.13
//  other feature use corresponding go version
//  the default go version is go1.18
// == tag
// any tag greater or eqaul to v6.3.xxx use go1.19
// any tag greater or eqaul to v6.0.xxx use go1.18
// any tag smaller than v6.0.0 and graeter or equal to v5.1.xxx use go1.16
// any tag smaller than v5.1.0 use go1.13


def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        if (branchNameOrTag >= "v6.1") {
            println "tag ${branchNameOrTag} use go 1.19"
            return "go1.19"
        }
        if (branchNameOrTag >= "v6.0") {
            println "tag ${branchNameOrTag} use go 1.18"
            return "go1.18"
        }
        if (branchNameOrTag >= "v5.1") {
            println "tag ${branchNameOrTag} use go 1.16"
            return "go1.16"
        }
        if (branchNameOrTag < "v5.1") {
            println "tag ${branchNameOrTag} use go 1.13"
            return "go1.13"
        }
        println "tag ${branchNameOrTag} use default version go 1.18"
        return "go1.19"
    } else { 
        println "this is a branch"
        if (branchNameOrTag in feature_branch_use_go13) {
            println "feature branch ${branchNameOrTag} use go 1.13"
            return "go1.13"
        }
        if (branchNameOrTag in feature_branch_use_go16) {
            println "feature branch ${branchNameOrTag} use go 1.16"
            return "go1.16"
        }
        if (branchNameOrTag in feature_branch_use_go18) {
            println "feature branch ${branchNameOrTag} use go 1.18"
            return "go1.18"
        }
        if (branchNameOrTag in feature_branch_use_go19) {
            println "feature branch ${branchNameOrTag} use go 1.19"
            return "go1.19"
        }
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.19")
            return "go1.19"
        }


        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-6.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.19")
            return "go1.19"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.3"  && branchNameOrTag >= "release-6.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.0" && branchNameOrTag >= "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.16")
            return "go1.16"
        }

        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-5.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.13")
            return "go1.13"
        }
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.18"
        return "go1.19"
    }
}

GO_VERSION = selectGoVersion(ghprbTargetBranch)
POD_GO_IMAGE = GO_IMAGE_MAP[GO_VERSION]
println "go version: ${GO_VERSION}"
println "go image: ${POD_GO_IMAGE}"



def run_with_pod(Closure body) {
    def label = POD_LABEL_MAP[GO_VERSION]
    def cloud = "kubernetes-ng"
    def namespace = "jenkins-ticdc"
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
                    nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                            serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
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


/**
 * List diff files in the pull request.
 */
def list_pr_diff_files() {
    def list_pr_files_api_url = "https://api.github.com/repos/pingcap/tiflow/pulls/${ghprbPullId}/files"
    withCredentials([string(credentialsId: 'github-api-token-test-ci', variable: 'github_token')]) { 
        response = httpRequest consoleLogResponseBody: false, 
            contentType: 'APPLICATION_JSON', httpMode: 'GET', 
            customHeaders:[[name:'Authorization', value:"token ${github_token}", maskValue: true]],
            url: list_pr_files_api_url, validResponseCodes: '200'

        def json = new groovy.json.JsonSlurper().parseText(response.content)

        echo "Status: ${response.status}"
        def files = []
        for (element in json) { 
            files.add(element.filename)
        }

        println "pr diff files: ${files}"
        return files
    }
}


/**
 * If all files matches the pattern, return true
 */
def pattern_match_all_files(pattern, files_list) {
    for (file in files_list) {
        if (!file.matches(pattern)) {
            println "diff file not matched: ${file}"
            return false
        }
    }

    return true
}

if (ghprbPullId != null && ghprbPullId != "" && !params.containsKey("triggered_by_upstream_pr_ci")) {
    def pr_diff_files = list_pr_diff_files()
    def pattern = /(^dm\/|^engine\/).*$/
    // if all diff files start with dm/, skip cdc integration test
    def matched = pattern_match_all_files(pattern, pr_diff_files)
    if (matched) {
        echo "matched, all diff files full path start with dm/ or engine/, current pr is dm/engine's pr(not related to ticdc), skip cdc integration test"
        currentBuild.result = 'SUCCESS'
        return 0
    } else {
        echo "not matched, some diff files not start with dm/ or engine/, need run the cdc integration test"
    }
}


catchError {
    run_with_pod {
        stage('Prepare') {
            container("golang") {
                def ws = pwd()
                deleteDir()

                dir("${ws}/go/src/github.com/pingcap/tiflow") {
                    def codeCacheInFileserverUrl = "${FILE_SERVER_URL}/download/cicd/daily-cache-code/src-tiflow.tar.gz"
                    def cacheExisted = sh(returnStatus: true, script: """
                        if curl --output /dev/null --silent --head --fail ${codeCacheInFileserverUrl}; then exit 0; else exit 1; fi
                        """)
                    if (cacheExisted == 0) {
                        println "get code from fileserver to reduce clone time"
                        println "codeCacheInFileserverUrl=${codeCacheInFileserverUrl}"
                        sh """
                        curl -C - --retry 3 -f -O ${codeCacheInFileserverUrl}
                        tar -xzf src-tiflow.tar.gz --strip-components=1
                        rm -f src-tiflow.tar.gz
                        """
                    } else {
                        println "get code from github"
                    }
                    try {
                        checkout(changelog: false, poll: false, scm: [
                                $class                           : "GitSCM",
                                branches                         : [
                                        [name: ghprbActualCommit],
                                ],
                                userRemoteConfigs                : [
                                        [
                                                url          : "git@github.com:pingcap/tiflow.git",
                                                refspec      : specStr,
                                                credentialsId: "github-sre-bot-ssh",
                                        ]
                                ],
                                extensions                       : [
                                        [$class             : 'SubmoduleOption',
                                        disableSubmodules  : false,
                                        parentCredentials  : true,
                                        recursiveSubmodules: true,
                                        trackingSubmodules : false,
                                        reference          : ''],
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                ],
                                doGenerateSubmoduleConfigurations: false,
                        ])
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            checkout(changelog: false, poll: false, scm: [
                                $class                           : "GitSCM",
                                branches                         : [
                                        [name: ghprbActualCommit],
                                ],
                                userRemoteConfigs                : [
                                        [
                                                url          : "git@github.com:pingcap/tiflow.git",
                                                refspec      : specStr,
                                                credentialsId: "github-sre-bot-ssh",
                                        ]
                                ],
                                extensions                       : [
                                        [$class             : 'SubmoduleOption',
                                        disableSubmodules  : false,
                                        parentCredentials  : true,
                                        recursiveSubmodules: true,
                                        trackingSubmodules : false,
                                        reference          : ''],
                                        [$class: 'PruneStaleBranch'],
                                        [$class: 'CleanBeforeCheckout'],
                                ],
                                doGenerateSubmoduleConfigurations: false,
                            ])
                        }
                    }
                }

                dir("${ws}/go/src/github.com/pingcap/ci") {
                    if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                        echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                        deleteDir()
                    }
                    try {
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                    } catch (info) {
                        retry(2) {
                            echo "checkout failed, retry.."
                            sleep 5
                            if (sh(returnStatus: true, script: '[ -d .git ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                                echo "Not a valid git folder: ${ws}/go/src/github.com/pingcap/ci"
                                deleteDir()
                            }
                            checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: "${ciRepoBranch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[refspec: specStr, url: "${ciRepoUrl}"]]]
                        }
                    }
                }

                stash includes: "go/src/github.com/pingcap/tiflow/**", name: "ticdc", useDefaultExcludes: false
            }
        }

        def script_path = "go/src/github.com/pingcap/ci/jenkins/pipelines/ci/ticdc/integration_test_common.groovy"
        def common = load script_path
        catchError {
            common.prepare_binaries()
            def label = TEST_POD_LABEL_MAP[GO_VERSION]
            podTemplate(
                    label: label,
                    cloud: "kubernetes-ng",
                    idleMinutes: 0,
                    namespace: "jenkins-ticdc",
                    containers: [
                            containerTemplate(
                                    name: 'golang', alwaysPullImage: true,
                                    image: "${POD_GO_IMAGE}", ttyEnabled: true,
                                    resourceRequestCpu: '2000m', resourceRequestMemory: '12Gi',
                                    command: '/bin/sh -c', args: 'cat',
                                    envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            ),
                    ],
                    volumes: [
                            nfsVolume(mountPath: '/home/jenkins/agent/ci-cached-code-daily', serverAddress: '172.16.5.22',
                                    serverPath: '/mnt/ci.pingcap.net-nfs/git', readOnly: false),
                            emptyDirVolume(mountPath: '/tmp', memory: true),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                    ],
            ) {
                common.tests("storage", label)
            }
            // If it is triggered upstream, there is no need to collect test coverage.
            if (!params.containsKey("triggered_by_upstream_pr_ci") && !params.containsKey("release_test")) {
                common.coverage()
            }
            currentBuild.result = "SUCCESS"
        }

        stage('Summary') {
            def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
            def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                    "${ghprbPullLink}" + "\n" +
                    "${ghprbPullDescription}" + "\n" +
                    "Integration Test Result: `${currentBuild.result}`" + "\n" +
                    "Elapsed Time: `${duration} mins` " + "\n" +
                    "${env.RUN_DISPLAY_URL}"

            if (currentBuild.result != "SUCCESS") {
                slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
            }
        }
    }   
}

if (params.containsKey("triggered_by_upstream_ci")  && params.get("triggered_by_upstream_ci") == "tiflow_merge_ci") {
    stage("update commit status") {
        node("master") {
            if (currentBuild.result == "ABORTED") {
                PARAM_DESCRIPTION = 'Jenkins job aborted'
                // Commit state. Possible values are 'pending', 'success', 'error' or 'failure'
                PARAM_STATUS = 'error'
            } else if (currentBuild.result == "FAILURE") {
                PARAM_DESCRIPTION = 'Jenkins job failed'
                PARAM_STATUS = 'failure'
            } else if (currentBuild.result == "SUCCESS") {
                PARAM_DESCRIPTION = 'Jenkins job success'
                PARAM_STATUS = 'success'
            } else {
                PARAM_DESCRIPTION = 'Jenkins job meets something wrong'
                PARAM_STATUS = 'error'
            }
            def default_params = [
                    string(name: 'TIFLOW_COMMIT_ID', value: ghprbActualCommit ),
                    string(name: 'CONTEXT', value: 'idc-jenkins-ci-ticdc/integration-test'),
                    string(name: 'DESCRIPTION', value: PARAM_DESCRIPTION ),
                    string(name: 'BUILD_URL', value: RUN_DISPLAY_URL ),
                    string(name: 'STATUS', value: PARAM_STATUS ),
            ]
            echo("default params: ${default_params}")
            build(job: "tiflow_update_commit_status", parameters: default_params, wait: true)
        }
    }
} else {
    stage("upload-pipeline-data") {
        taskFinishTime = System.currentTimeMillis()
        build job: 'upload-pipelinerun-data',
            wait: false,
            parameters: [
                    [$class: 'StringParameterValue', name: 'PIPELINE_NAME', value: "${JOB_NAME}"],
                    [$class: 'StringParameterValue', name: 'PIPELINE_RUN_URL', value: "${RUN_DISPLAY_URL}"],
                    [$class: 'StringParameterValue', name: 'REPO', value: "pingcap/tiflow"],
                    [$class: 'StringParameterValue', name: 'COMMIT_ID', value: ghprbActualCommit],
                    [$class: 'StringParameterValue', name: 'TARGET_BRANCH', value: ghprbTargetBranch],
                    [$class: 'StringParameterValue', name: 'JUNIT_REPORT_URL', value: resultDownloadPath],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST', value: ghprbPullId],
                    [$class: 'StringParameterValue', name: 'PULL_REQUEST_AUTHOR', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                    [$class: 'StringParameterValue', name: 'JOB_TRIGGER', value: params.getOrDefault("ghprbPullAuthorLogin", "default")],
                    [$class: 'StringParameterValue', name: 'TRIGGER_COMMENT_BODY', value: params.getOrDefault("ghprbCommentBody", "default")],
                    [$class: 'StringParameterValue', name: 'JOB_RESULT_SUMMARY', value: ""],
                    [$class: 'StringParameterValue', name: 'JOB_START_TIME', value: "${taskStartTimeInMillis}"],
                    [$class: 'StringParameterValue', name: 'JOB_END_TIME', value: "${taskFinishTime}"],
                    [$class: 'StringParameterValue', name: 'POD_READY_TIME', value: ""],
                    [$class: 'StringParameterValue', name: 'CPU_REQUEST', value: "4000m"],
                    [$class: 'StringParameterValue', name: 'MEMORY_REQUEST', value: "8Gi"],
                    [$class: 'StringParameterValue', name: 'JOB_STATE', value: currentBuild.result],
                    [$class: 'StringParameterValue', name: 'JENKINS_BUILD_NUMBER', value: "${BUILD_NUMBER}"],
        ]
    }
}





