// TODO: extract branch parsing codes to common function.

// checkout component src from git repo.
def checkout(gitUrl, keyInComment, prTargetBranch, prCommentBody, credentialsId="", trunkBranch="master") {
    // /run-xxx dep1=release-x.y
    final commentBodyReg = /\b${keyInComment}\s*=\s*([^\s\\]+)(\s|\\|$)/    
    // - release-6.2
    // - release-6.2-20220801
    // - 6.2.0-pitr-dev    
    final releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
    // - feature/abcd
    // - feature_abcd
    final featureBranchReg = /^feature[\/_].*/

    def componentBranch = prTargetBranch
    if (prCommentBody =~ commentBodyReg) {
        componentBranch = (prCommentBody =~ commentBodyReg)[0][1]
    } else if (prTargetBranch =~ releaseOrHotfixBranchReg) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
    } else if (prTargetBranch =~ featureBranchReg) {
       componentBranch = trunkBranch
    }

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
                [$class: 'CloneOption', timeout: 2],
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
    // /run-xxx dep1=release-x.y
    final commentBodyReg = /\b${keyInComment}\s*=\s*([^\s\\]+)(\s|\\|$)/    
    // - release-6.2
    // - release-6.2-20220801
    // - 6.2.0-pitr-dev    
    final releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
    // - feature/abcd
    // - feature_abcd
    final featureBranchReg = /^feature[\/_].*/

    def componentBranch = prTargetBranch
    if (prCommentBody =~ commentBodyReg) {
        componentBranch = (prCommentBody =~ commentBodyReg)[0][1]
    } else if (prTargetBranch =~ releaseOrHotfixBranchReg) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
    } else if (prTargetBranch =~ featureBranchReg) {
       componentBranch = trunkBranch
    }

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
