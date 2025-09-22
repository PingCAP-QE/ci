// compute component branch from pr info.
def computeBranchFromPR(String component, String prTargetBranch, String prTitle, String trunkBranch="master") {
    // pr title xxx | dep1=release-x.y
    println("computeBranchFromPR component: ${component}, prTargetBranch: ${prTargetBranch}, prTitle: ${prTitle}, trunkBranch: ${trunkBranch}")

    // <main pr title> (#<main pr number>)
    final cherryPickTitleReg = /\s+\(#\d+\)$/
    if (prTitle =~ cherryPickTitleReg) {
        println("The CI params in title seem to be inherited from the master PR. To set CI params, add them as a suffix to the title after the PR number (e.g., 'Fix: ... (#123) | component=value').")
    }

    final componentParamReg = /\b${component}\s*=\s*([^\s\\]+)(\s|\\|$)/

    // - release-6.2
    // - release-9.0-beta.1, it's new style for beta release.
    final releaseBranchReg = /^release\-((\d+\.\d+)(-beta\.\d+)?)$/
    // - feature/release-8.1-abcdefg
    // - feature_release-8.1-abcdefg
    final wipReleaseFeatureBranchReg = /^feature[\/_]release\-(\d+\.\d+)-.+/
    // - release-6.2-20220801
    final oldHotfixBranchReg = /^release\-(\d+\.\d+)-(\d+)$/

    // - release-6.1-20230101-v6.1.2
    final newHotfixBranchReg = /^release\-\d+\.\d+\-\d+\-v((\d+\.\d+)\.\d+)/
    // - feature/release-8.1.1-abcdefg
    // - feature_release-8.1.1-abcdefg
    final historyReleaseFeatureBranchReg = /^feature[\/_]release\-((\d+\.\d+)\.\d+)-.+/

    // - feature/abcd
    // - feature_abcd
    final featureBranchReg = /^feature[\/_].*/

    // the components that will created the patch release branch when version released: release-X.Y.Z
    final componentsSupportPatchReleaseBranch = ['tidb-test', 'plugin']

    def componentBranch = prTargetBranch
    if (prTitle =~ componentParamReg && !(prTitle =~ cherryPickTitleReg)) {
        // example PR tiltes:
        // - feat: add new feature | tidb=pr/123
        // - feat: add new faeture | tidb=release-8.1
        // - feat: add new faeture | tidb=<tidb-repo-commit-sha1>
        componentBranch = (prTitle =~ componentParamReg)[0][1]
    } else if (prTargetBranch =~ releaseBranchReg ) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ releaseBranchReg)[0][1]) // => release-X.Y or release-X.Y-beta.M
    } else if (prTargetBranch =~ wipReleaseFeatureBranchReg ) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ wipReleaseFeatureBranchReg)[0][1]) // => release-X.Y
    } else if (prTargetBranch =~ oldHotfixBranchReg) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ oldHotfixBranchReg)[0][1]) // => release-X.Y
    } else if (prTargetBranch =~ newHotfixBranchReg) {
        if (componentsSupportPatchReleaseBranch.contains(component)) {
            componentBranch = String.format('release-%s', (prTargetBranch =~ newHotfixBranchReg)[0][1]) // => release-X.Y.Z
        } else {
            componentBranch = String.format('release-%s', (prTargetBranch =~ newHotfixBranchReg)[0][2]) // => release-X.Y
        }
    } else if (prTargetBranch =~ historyReleaseFeatureBranchReg) {
        if (componentsSupportPatchReleaseBranch.contains(component)) {
            componentBranch = String.format('release-%s', (prTargetBranch =~ historyReleaseFeatureBranchReg)[0][1]) // => release-X.Y.Z
        } else {
            componentBranch = String.format('release-%s', (prTargetBranch =~ historyReleaseFeatureBranchReg)[0][2]) // => release-X.Y
        }
    } else if (prTargetBranch =~ featureBranchReg) {
        componentBranch = trunkBranch
    }

    return componentBranch
}

// checkout component src from git repo.
def checkout(gitUrl, component, prTargetBranch, prTitle, credentialsId="", trunkBranch="master", timeout=5) {
    def componentBranch = computeBranchFromPR(component, prTargetBranch, prTitle,  trunkBranch)
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

def checkoutV2(gitUrl, component, prTargetBranch, prTitle, credentialsId="", trunkBranch="master", timeout=5) {
    def componentBranch = computeBranchFromPR(component, prTargetBranch, prTitle,  trunkBranch)
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
// if title contains | tidb-test=pr/xxx，fetch tidb-test from pr/xxx (with merge base branch)
// for single PR: support pr title specify tidb-test=release-x.y or tidb-test=commit-hash or tidb-test=pr/xxx
// for multi PR: support pr title specify tidb-test=pr/xxx or not specify tidb-test
def checkoutSupportBatch(gitUrl, component, prTargetBranch, prTitle, refs, credentialsId="", trunkBranch="master", timeout=5) {
    def tidbTestRefs = [] // List of tidb-test refs PR:123, PR:456
    boolean branchOrCommitSpecified = false // Flag to check if a branch or commit is specified in any PR title

    def componentBranch = prTargetBranch

    // compute the branch.
    refs.pulls.each { pull ->
        componentBranch = computeBranchFromPR(component, prTargetBranch, pull.title,  trunkBranch)
        if (componentBranch.startsWith("pr/")) {
            tidbTestRefs.add("PR:${componentBranch}") // Add as PR reference
        } else {
            // 1. some PR title contains a branch or commit
            // 2. hotfix branch or feature branch
            if (prTargetBranch != componentBranch) {
                tidbTestRefs.add("Branch:${componentBranch}")
            }
        }
    }
    def (valid, filteredRefs) = validateAndFilterRefs(tidbTestRefs)
    if (!valid) {
        echo "Error: invalid ${component} refs in multiple PRs: ${filteredRefs}"
        throw new Exception("Error: invalid ${component} refs in multiple PRs.")
    } else {
        if (filteredRefs.isEmpty()) {
            echo "No tidb-test refs specified in PR title, checkout the default branch ${componentBranch} of ${component}."
            checkoutSingle(gitUrl, componentBranch, componentBranch, credentialsId)
        } else if (filteredRefs.size() == 1 && filteredRefs[0].startsWith("Branch:")) {
            // 1. feature branch or hotfix branch
            // 2. Single PR with branch or commit sha specified
            def branch = filteredRefs[0].split(":")[1]
            println("Checkout the branch: ${branch} of ${component}")
            checkoutSingle(gitUrl, prTargetBranch, branch, credentialsId)
        } else if (filteredRefs.size() == 1 && filteredRefs[0].startsWith("PR:")) {
            // 1. single PR with PR specified
            // 2. multi PR with the same PR of tidb-test specified
            def componentPr = filteredRefs[0].split(":")[1]
            println("Checkout the PR: ${componentPr} of ${component}")
            println("Note: When specifying tidb-test=pr/xxx in the PR title, the base branch of tidb-test pr must be the same as the base branch of tidb pr.If not, please specify tidb-test=<branch> or tidb-test=<commit-hash>.")
            checkoutPRWithPreMerge(gitUrl, prTargetBranch, filteredRefs, credentialsId)
        } else {
            // multi PR specified component PR (notice: for batch merge with specific branch is not supported)
            println("Note: When specifying tidb-test=pr/xxx in the PR title, the base branch of tidb-test pr must be the same as the base branch of tidb pr.If not, please specify tidb-test=<branch> or tidb-test=<commit-hash>.")
            checkoutPRWithPreMerge(gitUrl, prTargetBranch, filteredRefs.collect { it.split(":")[1] } as List, credentialsId)
        }
    }
}

def checkoutSingle(gitUrl, prTargetBranch, branchOrCommit, credentialsId, timeout=5) {
    def refSpec = "+refs/heads/*:refs/remotes/origin/*"
    // if branchOrCommit is sha1, then use it as refSpec
    if (branchOrCommit.length() == 40) {
        println("branchOrCommit is sha1, fetch pr refs and use it as refSpec")
        refSpec += " +refs/pull/*/head:refs/remotes/origin/pr/*"
    }
    checkout(
        changelog: false,
        poll: true,
        scm: [
            $class: 'GitSCM',
            branches: [[name: branchOrCommit]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [
                [$class: 'PruneStaleBranch'],
                [$class: 'CleanBeforeCheckout'],
                [$class: 'CloneOption', timeout: timeout],
            ],
            submoduleCfg: [],
            userRemoteConfigs: [[
                credentialsId: credentialsId,
                refspec: refSpec,
                url: gitUrl,
            ]]
        ]
    )
}

def checkoutPRWithPreMerge(gitUrl, prTargetBranch, tidbTestRefsList, credentialsId) {
    // iterate over tidbTestRefs and checkout all pr with pre-merge
    sshagent(credentials: [credentialsId]) {
        sh label: 'Know hosts', script: """#!/usr/bin/env bash
            [ -d ~/.ssh ] || mkdir ~/.ssh && chmod 0700 ~/.ssh
            ssh-keyscan -t rsa,dsa github.com >> ~/.ssh/known_hosts
        """
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

            # fetch and checkout target branch
            refSpec="+refs/heads/${prTargetBranch}:refs/remotes/origin/${prTargetBranch}"
            git fetch --force --verbose --prune --prune-tags -- ${gitUrl} \${refSpec}
            git checkout -f origin/${prTargetBranch}
            echo "🧾 HEAD info:"
            git rev-parse HEAD^{commit}
            git log -n 3 --oneline

            # iterate over tidbTestRefsList, then checkout pr and merge into base branch
            for ref in ${tidbTestRefsList}; do
                echo "🔍 fetch \${ref} and merge into ${prTargetBranch} branch"
                prNumber=\$(echo "\${ref}" | sed 's/[^0-9]*//g')
                refSpec="+refs/pull/\${prNumber}/head:refs/remotes/origin/pr/\${prNumber}/head"
                git fetch --force --verbose --prune --prune-tags -- ${gitUrl} \${refSpec}

                # Merge the PR into the target branch
                # --no-edit uses the default commit message without launching an editor
                # If there is a merge conflict, the "||" part will execute
                echo "🔀 Merge \${ref} into ${prTargetBranch}"
                git merge  origin/pr/\${prNumber}/head --no-edit --no-ff || {
                    echo "ERROR: Merge conflict detected. Exiting with error."
                    exit 1
                }

                echo "🧾 Merge result:"
                git rev-parse HEAD^{commit}
                git log -n 3 --oneline
                echo "✅ Merge pr/\${prNumber} to base branch 🎉"
            done

            git clean -ffdx
            git rev-parse --show-toplevel
            git status -s .

            echo "✅ ~~~~~ All done. ~~~~~~"
            """
        )
    }
}


// default fetch targetBranch
// if title contains | tidb=pr/xxx，fetch tidb from pr/xxx (with merge base branch)
def checkoutWithMergeBase(gitUrl, component, prTargetBranch, prTitle, trunkBranch="master", timeout=5, credentialsId="") {
    // example pr tilte : "feat: add new feature | tidb=pr/123"
    // componentBranch = pr/123
    // componentBranch = release-6.2
    // componentBranch = master
    def componentBranch = computeBranchFromPR(component, prTargetBranch, prTitle, trunkBranch)
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
            echo "🔍 fetch ${component} ${componentBranch} and merge ${prTargetBranch} branch"
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
// Note: useBranchInArtifactUrl is used for tiflash component, only support master branch and common release branch
def fetchAndExtractArtifact(serverUrl, component, prTargetBranch, prTitle, artifactPath, pathInArchive="", trunkBranch="master", artifactVerify=false, useBranchInArtifactUrl=false) {
    def componentBranch = computeBranchFromPR(component, prTargetBranch, prTitle, trunkBranch)
    sh(label: 'download and extract from server', script: """
        sha1=""

        if [[ "commit_${componentBranch}" =~ ^commit_[0-9a-f]{40}\$ ]]; then
            sha1=${componentBranch}
        else
            refUrl="${serverUrl}/download/refs/pingcap/${component}/${componentBranch}/sha1"
            if [[ "${artifactVerify}" = "true" ]]; then
                refUrl="${serverUrl}/download/refs/pingcap/${component}/${componentBranch}/sha1.verify"
            fi
            echo "🔍 ref url: \${refUrl}"
            sha1="\$(curl --fail \${refUrl} | head -1)"
        fi

        artifactUrl="${serverUrl}/download/builds/pingcap/${component}/\${sha1}/${artifactPath}"
        if [[ "${useBranchInArtifactUrl}" = "true" ]]; then
            artifactUrl="${serverUrl}/download/builds/pingcap/${component}/${componentBranch}/\${sha1}/${artifactPath}"
        fi
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

def ks3_upload_fileserver(local, remote, credentialsId="ks3util-config"){
    withCredentials(
        [file(credentialsId: "${credentialsId}", variable: 'KS3UTIL_CONF')]
    ) {
        sh "ks3util -c \$KS3UTIL_CONF cp -f $local ks3://ee-fileserver/download/${remote}"
    }
}

def ks3_download_fileserver(remote, local, credentialsId="ks3util-config"){
    withCredentials(
        [file(credentialsId: "${credentialsId}", variable: 'KS3UTIL_CONF')]
    ) {
        sh "ks3util -c \$KS3UTIL_CONF cp -f ks3://ee-fileserver/download/${remote} $local"
    }
}

def validateAndFilterRefs(componentRefs) {
    if (componentRefs.isEmpty()) {
        return [true, []]
    }

    def prRefs = componentRefs.findAll { it.startsWith("PR:") }
    def branchRefs = componentRefs.findAll { it.startsWith("Branch:") }

    // Check if all refs are PRs
    // if all refs are PRs, need to merge the PRs with the same base branch
    if (prRefs.size() == componentRefs.size()) {
        return [true, prRefs.unique()]
    }

    // Check if all refs are Branches and there's only one unique branch
    // 1. for hotfix branch batch merge, valid
    // 2. for feature branch batch merge, valid
    // 3. for multi PR with different branches, invalid
    if (branchRefs.size() == componentRefs.size()) {
        def uniqueBranches = branchRefs.unique()
        if (uniqueBranches.size() > 1) {
            // Multiple PR with different branches specified - not supported
            return [false, uniqueBranches]
        }
        return [true, uniqueBranches]
    }

    // Mixed refs is invalid - return false and the combined unique refs
    return [false, (prRefs + branchRefs).unique()]
}

// Extract hotfix version tag from branch name
// Returns a map containing:
//   - isHotfix: boolean indicating if this is a hotfix branch
//   - versionTag: the version tag (e.g. 'v7.1.1') if it's a hotfix branch, null otherwise
def extractHotfixInfo(String branchName) {
    def hotfixPattern = ~/^release-\d+\.\d+-\d{8}-(v\d+\.\d+\.\d+)(?:-.*)?$/
    def matcher = branchName =~ hotfixPattern
    def isHotfix = matcher.matches()
    def versionTag = isHotfix ? matcher[0][1] : null
    return [isHotfix: isHotfix, versionTag: versionTag]
}

/**
 * Parse component versions from PR comment and compute final branches to use.
 * This function first tries to extract component versions from PR comment body,
 * then falls back to computeBranchFromPR for components not specified in comment.
 * 
 * @param refs The REFS object containing PR information
 * @param components List of component names to parse (e.g., ['tidb', 'tikv', 'pd', 'tiflash'])
 * @param trunkBranch Default trunk branch name (default: 'master')
 * @return Map containing component names as keys and their resolved branches as values
 */
def parseComponentVersionsFromComment(def refs, List<String> components, String trunkBranch = 'master') {
    def result = [:]
    
    // Get comment body from PR
    def commentBody = ""
    try {
        if (refs?.pulls && refs.pulls.size() > 0) {
            commentBody = refs.pulls[0]?.body ?: ""
        }
    } catch (Exception e) {
        println "Warning: Failed to extract comment body: ${e.message}"
        commentBody = ""
    }
    
    println "Parsing component versions from comment: ${commentBody}"
    
    // Parse each component
    components.each { componentName ->
        def componentBranch = refs.base_ref
        def prTitle = refs?.pulls?.get(0)?.title ?: ""
        
        // Try to parse from comment first
        def commentPattern = /${componentName}\s*=\s*([^\s\\]+)(\s|\\|$)/
        def matcher = commentBody =~ commentPattern
        
        if (matcher) {
            componentBranch = "${matcher[0][1]}"
            println "Using ${componentName} branch from comment: ${componentBranch}"
        } else {
            // Fallback to computeBranchFromPR
            componentBranch = computeBranchFromPR(componentName, refs.base_ref, prTitle, trunkBranch)
            println "Using ${componentName} branch from computeBranchFromPR: ${componentBranch}"
        }
        
        result[componentName] = componentBranch
    }
    
    return result
}
