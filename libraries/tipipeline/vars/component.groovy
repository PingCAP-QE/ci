def checkout(gitUrl, keyInComment, prTargetBranch, prCommentBody, credentialsId="") {
    //  - release-6.2
    //  - release-6.2-20220801
    //  - 6.2.0-pitr-dev    
    def releaseOrHotfixBranchReg = /^(release\-)?(\d+\.\d+)(\.\d+\-.+)?/
    // /run-xxx dep1=release-x.y
    def commentBodyReg = /\b${keyInComment}\s*=\s*([^\s\\]+)(\s|\\|$)/
    def componentBranch = prTargetBranch
    if (prCommentBody =~ commentBodyReg) {
        componentBranch = (prCommentBody =~ commentBodyReg)[0][1]
    } else if (prTargetBranch =~ releaseOrHotfixBranchReg) {
        componentBranch = String.format('release-%s', (prTargetBranch =~ releaseOrHotfixBranchReg)[0][2])
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