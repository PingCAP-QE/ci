// compute component branch from pr info.
def computeBranchFromPR(String keyInComment, String prTargetBranch, String prCommentBody, String trunkBranch="master") {
    // /run-xxx dep1=release-x.y
    final commentBodyReg = /\b${keyInComment}\s*=\s*([^\s\\]+)(\s|\\|$)/
    // - release-6.2
    // - release-6.2-20220801
    // - 6.2.0-pitr-dev    
    final releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
    // - release-6.1-20230101-v6.1.2
    final newHotfixBranchReg = /^release\-\d+\.\d+\-\d+\-v(\d+\.\d+\.\d+)/
    // - feature/abcd
    // - feature_abcd
    final featureBranchReg = /^feature[\/_].*/

    // the components that will created the patch release branch when version released: release-X.Y.Z
    final componentsSupportPatchReleaseBranch = ['tidb-test']

    def componentBranch = prTargetBranch
    if (prCommentBody =~ commentBodyReg) {
        componentBranch = (prCommentBody =~ commentBodyReg)[0][1]
    } else if (prTargetBranch =~ newHotfixBranchReg && componentsSupportPatchReleaseBranch.contains(keyInComment)) {        
        componentBranch = String.format('release-%s', (prTargetBranch =~ newHotfixBranchReg)[0][1])
    } else if (prTargetBranch =~ releaseOrHotfixBranchReg) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
    } else if (prTargetBranch =~ featureBranchReg) {
       componentBranch = trunkBranch
    }

    return componentBranch
}

// checkout component src from git repo.
def checkout(gitUrl, keyInComment, prTargetBranch, prCommentBody, credentialsId="", trunkBranch="master", timeout=5) {
    def componentBranch = computeBranchFromPR(keyInComment, prTargetBranch, prCommentBody,  trunkBranch)
    def pluginSpec = "+refs/heads/*:refs/remotes/origin/*"
    // transfer plugin branch from pr/28 to origin/pr/28/head
    if (componentBranch.startsWith("pr/")) {
        pluginSpec = "+refs/pull/*:refs/remotes/origin/pr/*"
        componentBranch = "origin/${componentBranch}/head"
    }

    checkout(
        changelog: false,
        poll: true,
        scm: [
            $class: 'GitSCM',
            branches: [[name: componentBranch]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'PruneStaleBranch'],
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', timeout: timeout],
            ], 
            submoduleCfg: [],
            userRemoteConfigs: [[
                credentialsId: credentialsId,
                refspec: pluginSpec,
                url: gitUrl,
            ]]
        ]
    )
}

// fetch component artifact from artifactory(current http server)
def fetchAndExtractArtifact(serverUrl, keyInComment, prTargetBranch, prCommentBody, artifactPath, pathInArchive="", trunkBranch="master") {
    def componentBranch = computeBranchFromPR(keyInComment, prTargetBranch, prCommentBody,  trunkBranch)

    sh(label: 'download and extract from server', script: """
        sha1=""

        if [[ "commit_${componentBranch}" =~ ^commit_[0-9a-f]{40}\$ ]]; then
            sha1=${componentBranch}
        else
            refUrl="${serverUrl}/download/refs/pingcap/${keyInComment}/${componentBranch}/sha1"
            echo "🔍 ref url: \${refUrl}"
            sha1="\$(curl --fail \${refUrl} | head -1)"
        fi
        
        artifactUrl="${serverUrl}/download/builds/pingcap/${keyInComment}/\${sha1}/${artifactPath}"
        echo "📦 artifact url: \${artifactUrl}"
        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 0 -O - "\${artifactUrl}" | tar xz ${pathInArchive}
    """)
}


def getDiffFiles(fullRepoName, prId, credentialsId) {
    withCredentials([string(credentialsId: "${credentialsId}", variable: 'token')]) { 
        def apiUrl = "https://api.github.com/repos/${fullRepoName}/pulls/${prId}/files"
        def headers = ['Authorization': "Bearer ${token}"]
        def response = httpRequest(url: apiUrl, contentType: 'APPLICATION_JSON', httpMode: 'GET', headers: headers)
        if (response.status != 200) {
            error("Failed to retrieve diff files from GitHub API: ${response.status} ${response.content}")
            return []
        }
        def files = new groovy.json.JsonSlurper().parseText(response.content)
        return files.collect { it.filename }
    }
}

/**
 * If all files matches the pattern, return true
 */
def patternMatchAllFiles(pattern, files_list) {
    for (file in files_list) {
        if (!file.matches(pattern)) {
            println "diff file not matched: ${file}"
            return false
        }
    }

    return true
}

/**
 * If all files matches the pattern, return true
 */
def patternMatchAnyFile(pattern, files_list) {
    for (file in files_list) {
        if (file.matches(pattern)) {
            println "diff file matched: ${file}"
            return true
        }
    }

    return false
}