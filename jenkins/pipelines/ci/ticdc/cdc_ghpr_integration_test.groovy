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

GO_VERSION = "go1.21"
POD_GO_IMAGE = "hub.pingcap.net/wulifu/golang-tini:1.21"

feature_branch_use_go13 = []
feature_branch_use_go16 = ["hz-poc", "ft-data-inconsistency", "br-stream"]
feature_branch_use_go18 = ["release-multi-source", "fb/latency"]
feature_branch_use_go19 = []
feature_branch_use_go20 = []

def taskStartTimeInMillis = System.currentTimeMillis()
def k8sPodReadyTime = System.currentTimeMillis()
def taskFinishTime = System.currentTimeMillis()
resultDownloadPath = ""

// Version Selector
// branch or tag
// == branch
//  master use go1.21
//  release branch >= release-7.4 use go1.21
//  release branch >= release-7.0 use go1.20
//  release branch >= release-6.1 use go1.19
//  release branch >= release-6.0 use go1.18
//  release branch >= release-5.1 use go1.16
//  release branch < release-5.0 use go1.13
//  other feature use corresponding go version
//  the default go version is go1.21


def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
        if (branchNameOrTag >= "v7.4") {
            println "tag ${branchNameOrTag} use go 1.21"
            return "go1.21"
        }
        if (branchNameOrTag >= "v7.0") {
            println "tag ${branchNameOrTag} use go 1.20"
            return "go1.20"
        }
        // special for v6.1 larger than patch 3
        if (branchNameOrTag.startsWith("v6.1") && branchNameOrTag >= "v6.1.3" || branchNameOrTag=="v6.1.0-nightly") {
            return "go1.19"
        }
        if (branchNameOrTag >= "v6.3") {
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
        println "tag ${branchNameOrTag} use default version go 1.21"
        return "go1.21"
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
        if (branchNameOrTag in feature_branch_use_go20) {
            println "feature branch ${branchNameOrTag} use go 1.20"
            return "go1.20"
        }
        if (branchNameOrTag == "master") {
            println("branchNameOrTag: master  use go1.21")
            return "go1.21"
        }

        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-7.4") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.21")
            return "go1.21"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-7.0") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.20")
            return "go1.20"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-7.0" && branchNameOrTag >= "release-6.1") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.19")
            return "go1.19"
        }
        if (branchNameOrTag.startsWith("release-") && branchNameOrTag < "release-6.1"  && branchNameOrTag >= "release-6.0") {
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
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.21"
        return "go1.21"
    }
}

def selectGoImageWithTini(branchNameOrTag) {
    // "hub.pingcap.net/jenkins/centos7_golang-1.21:latest"
    def goVersion = selectGoVersion(branchNameOrTag).substring(2)
    def goImage = "hub.pingcap.net/jenkins/golang-tini:${goVersion}"
    println "goImage: ${goImage}"
    return goImage
}

POD_GO_IMAGE = selectGoImageWithTini(ghprbTargetBranch)
println "go image: ${POD_GO_IMAGE}"

podYAML = '''
apiVersion: v1
kind: Pod
spec:
  nodeSelector:
    enable-ci: true
    ci-nvme-high-performance: true
  tolerations:
  - key: dedicated
    operator: Equal
    value: test-infra
    effect: NoSchedule
'''


def run_with_pod(Closure body) {
    def label = "${JOB_NAME}-build-${BUILD_NUMBER}"
    def cloud = "kubernetes-ksyun"
    def namespace = "jenkins-tiflow"
    def jnlp_docker_image = "jenkins/inbound-agent:3148.v532a_7e715ee3-10"
    podTemplate(label: label,
            cloud: cloud,
            namespace: namespace,
            yaml: podYAML,
            yamlMergeStrategy: merge(),
            idleMinutes: 0,
            nodeSelector: "kubernetes.io/arch=amd64",
            containers: [
                    containerTemplate(
                        name: 'golang', alwaysPullImage: true,
                        image: "${POD_GO_IMAGE}", ttyEnabled: true,
                        resourceRequestCpu: '4000m', resourceRequestMemory: '8Gi',
                        args: 'cat',
                        envVars: [containerEnvVar(key: 'GOPATH', value: '/go')]
                    )
            ],
            volumes: [
                    emptyDirVolume(mountPath: '/tmp', memory: false),
                    emptyDirVolume(mountPath: '/home/jenkins', memory: false)
                    ],
    ) {
        node(label) {
            println "debug command:\nkubectl -n ${namespace} exec -ti ${NODE_NAME} -c golang bash"
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
            common.download_binaries()
            def label = "${JOB_NAME}-test-${BUILD_NUMBER}"
            podTemplate(
                    label: label,
                    cloud: "kubernetes-ksyun",
                    yaml: podYAML,
                    yamlMergeStrategy: merge(),
                    idleMinutes: 0,
                    nodeSelector: "kubernetes.io/arch=amd64",
                    namespace: "jenkins-tiflow",
                    containers: [
                            containerTemplate(
                                    name: 'golang', alwaysPullImage: true,
                                    image: "${POD_GO_IMAGE}", ttyEnabled: true,
                                    resourceRequestCpu: '4000m', resourceRequestMemory: '12Gi',
                                    args: 'cat',
                                    envVars: [containerEnvVar(key: 'GOPATH', value: '/go')],
                            ),
                    ],
                    volumes: [
                            emptyDirVolume(mountPath: '/tmp', memory: true),
                            emptyDirVolume(mountPath: '/home/jenkins', memory: true)
                    ],
            ) {
                common.tests("mysql", label)
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
