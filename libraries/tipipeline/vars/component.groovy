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
    final componentsSupportPatchReleaseBranch = ['tidb-test', 'plugin']

    def componentBranch = prTargetBranch
    // example pr tilte : "feat: add new feature | tidb=pr/123"
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

def checkoutV2(gitUrl, keyInComment, prTargetBranch, prCommentBody, credentialsId="", trunkBranch="master", timeout=5) {
    def componentBranch = computeBranchFromPR(keyInComment, prTargetBranch, prCommentBody,  trunkBranch)
    def pluginSpec = "+refs/heads/$prTargetBranch:refs/remotes/origin/$prTargetBranch"
    // transfer plugin branch from pr/28 to origin/pr/28/head
    if (componentBranch.startsWith("pr/")) {
        def prId = componentBranch.substring(3)
        pluginSpec += " +refs/pull/$prId/head:refs/remotes/origin/pr/$prId/head"
        componentBranch = "origin/${componentBranch}/head"
    }
    println(gitUrl)
    println(pluginSpec)

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

// default fetch targetBranch
// if title contains | tidb=pr/xxx，fetch tidb from pr/xxx (with merge base branch)
def checkoutWithMergeBase(gitUrl, keyInComment, prTargetBranch, prCommentBody, trunkBranch="master", timeout=5, credentialsId="") {
    // example pr tilte : "feat: add new feature | tidb=pr/123"
    // componentBranch = pr/123
    // componentBranch = release-6.2
    // componentBranch = master
    def componentBranch = computeBranchFromPR(keyInComment, prTargetBranch, prCommentBody, trunkBranch)
    sh(label: 'checkout', script: """#!/usr/bin/env bash
        set -e
        git --version
        git init
        git rev-parse --resolve-git-dir .git

        # Configure git to enable non-interactive operation
        git config --global user.email "ti-chi-bot@ci" && git config --global user.name "TiChiBot"
        # Add the original repository as a remote if it hasn't been added
        git config remote.origin.url ${gitUrl}
        git config core.sparsecheckout true

        # reset & clean
        git reset --hard
        git clean -ffdx

        refSpec="+refs/heads/${prTargetBranch}:refs/remotes/origin/${prTargetBranch}"

        ## checkout PR and merge base branch
        if [[ ${componentBranch} == pr/* ]]; then
            echo "🔍 fetch ${keyInComment} ${componentBranch} and merge ${prTargetBranch} branch"
            prNumber=\$(echo "${componentBranch}" | sed 's/[^0-9]*//g')
            refSpec="\${refSpec} +refs/pull/\${prNumber}/head:refs/remotes/origin/pr/\${prNumber}/head"
            git fetch --force --verbose --prune --prune-tags -- ${gitUrl} \${refSpec}
            git checkout -f origin/pr/\${prNumber}/head
            echo "🧾 HEAD info:"
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline

            # Merge the latest target branch into the PR branch
            # --no-edit uses the default commit message without launching an editor
            # If there is a merge conflict, the "||" part will execute
            echo "🔀 Merge ${prTargetBranch} into ${componentBranch}"
            git merge  origin/${prTargetBranch} --no-edit --no-ff || {
                echo "ERROR: Merge conflict detected. Exiting with error."
                exit 1
            }
            echo "🧾 Merge result:"
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline
            echo "✅ Merge base branch 🎉"
        else
            git fetch --force --verbose --prune --prune-tags -- ${gitUrl} \${refSpec}
            git checkout -f origin/${prTargetBranch}
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline
            echo "✅ Checkout ${prTargetBranch} 🎉"
        fi

        git clean -ffdx
        git rev-parse --show-toplevel
        git status -s .

        echo "✅ ~~~~~ All done. ~~~~~~"
    """)
}

// fetch component artifact from artifactory(current http server)
def fetchAndExtractArtifact(serverUrl, keyInComment, prTargetBranch, prCommentBody, artifactPath, pathInArchive="", trunkBranch="master", artifactVerify=false) {
    def componentBranch = computeBranchFromPR(keyInComment, prTargetBranch, prCommentBody, trunkBranch)
    sh(label: 'download and extract from server', script: """
        sha1=""

        if [[ "commit_${componentBranch}" =~ ^commit_[0-9a-f]{40}\$ ]]; then
            sha1=${componentBranch}
        else
            refUrl="${serverUrl}/download/refs/pingcap/${keyInComment}/${componentBranch}/sha1"
            if [[ "${artifactVerify}" = "true" ]]; then
                refUrl="${serverUrl}/download/refs/pingcap/${keyInComment}/${componentBranch}/sha1.verify"
            fi
            echo "🔍 ref url: \${refUrl}"
            sha1="\$(curl --fail \${refUrl} | head -1)"
        fi
        
        artifactUrl="${serverUrl}/download/builds/pingcap/${keyInComment}/\${sha1}/${artifactPath}"
        echo "⬇️📦 artifact url: \${artifactUrl}"
        saveFile=\$(basename \${artifactUrl})
        wget -q --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 -t 3 -c -O \${saveFile} \${artifactUrl}
        echo "📂 extract ${pathInArchive} from \${saveFile} ..."
        tar -xzf \${saveFile} ${pathInArchive}
        rm \${saveFile}
        echo "✅ extracted ${pathInArchive} from \${saveFile} ."
    """)
}


def getPrDiffFiles(fullRepoName, prId, credentialsId) {
    withCredentials([string(credentialsId: "${credentialsId}", variable: 'token')]) { 
        def apiUrl = "https://api.github.com/repos/${fullRepoName}/pulls/${prId}/files"
        def allFiles = []
        def page = 1
        while (true) {
            def pagedUrl = apiUrl + "?page=${page}&per_page=100"
            def response = httpRequest(url: pagedUrl, contentType: 'APPLICATION_JSON', 
                httpMode: 'GET', customHeaders: [[name: 'Authorization', value: "token $token", maskValue: true]])
            if (response.status != 200) {
                error("Failed to retrieve diff files from GitHub API: ${response.status} ${response.content}")
                return []
            }
            def files = new groovy.json.JsonSlurper().parseText(response.content)
            if (files.size() == 0) {
                break
            }
            allFiles.addAll(files.collect { it.filename })
            page++
        }
        return allFiles
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
