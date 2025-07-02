feature_branch_use_go13 = []
feature_branch_use_go16 = ["hz-poc", "ft-data-inconsistency"]
feature_branch_use_go18 = ["release-multi-source", "br-stream", "refactor-syncer", "fb/latency"]
feature_branch_use_go19 = []
feature_branch_use_go20 = []

// Version Selector
// branch or tag
// == branch
//  master use go1.20
//  release branch >= release-7.0 use go1.20
//  release branch >= release-6.1 use go1.19
//  release branch >= release-6.0 use go1.18
//  release branch >= release-5.1 use go1.16
//  release branch < release-5.0 use go1.13
//  other feature use corresponding go version
//  the default go version is go1.20
// == tag
// any tag greater or eqaul to v7.0.xxx use go1.20
// any tag greater or eqaul to v6.1.xxx use go1.19
// any tag greater or eqaul to v6.0.xxx use go1.18
// any tag smaller than v6.0.0 and graeter or equal to v5.1.xxx use go1.16
// any tag smaller than v5.1.0 use go1.13


def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
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
        println "tag ${branchNameOrTag} use default version go 1.20"
        return "go1.20"
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
            println("branchNameOrTag: master  use go1.20")
            return "go1.20"
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
        println "branchNameOrTag: ${branchNameOrTag}  use default version go1.20"
        return "go1.20"
    }
}

return this
