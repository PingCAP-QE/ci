

feature_branch_use_go13 = []
feature_branch_use_go16 = ["hz-poc", "ft-data-inconsistency"]
feature_branch_use_go18 = ["release-multi-source", "br-stream", "refactor-syncer", "fb/latency"]
feature_branch_use_go19 = []

// Version Selector
// branch or tag
// == branch
//  master use go1.18
//  release branch >= release-6.0 use go1.18
//  release branch >= release-5.1 use go1.16
//  release branch < release-5.0 use go1.13
//  other feature use corresponding go version
//  the default go version is go1.18
// == tag
// any tag greater or eqaul to v6.0.xxx use go1.18
// any tag smaller than v6.0.0 and graeter or equal to v5.1.xxx use go1.16
// any tag smaller than v5.1.0 use go1.13


def selectGoVersion(branchNameOrTag) {
    if (branchNameOrTag.startsWith("v")) {
        println "This is a tag"
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
        println "tag ${branchNameOrTag} use default version go 1.18"
        return "go1.18"
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


        if (branchNameOrTag.startsWith("release-") && branchNameOrTag >= "release-6.3") {
            println("branchNameOrTag: ${branchNameOrTag}  use go1.18")
            return "go1.18"
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


// assert selectGoVersion("v6.3.0") == "go1.19"
// assert selectGoVersion("v6.0.0") == "go1.18"
// assert selectGoVersion("v6.0.1") == "go1.18"
// assert selectGoVersion("v6.2.3") == "go1.18"
// assert selectGoVersion("v6.0.1") == "go1.18"
// assert selectGoVersion("v6.1.1") == "go1.18"
// assert selectGoVersion("v5.1.0") == "go1.16"
// assert selectGoVersion("v5.2.3") == "go1.16"
// assert selectGoVersion("v5.3.0") == "go1.16"
// assert selectGoVersion("v5.4.0") == "go1.16"
// assert selectGoVersion("v5.4.3") == "go1.16"
// assert selectGoVersion("v5.0.0") == "go1.13"
// assert selectGoVersion("v5.0.6") == "go1.13"
// assert selectGoVersion("v4.0.0") == "go1.13"
// assert selectGoVersion("v3.0.0") == "go1.13"
// assert selectGoVersion("v5.1.0-20220202") == "go1.16"
// assert selectGoVersion("v5.2.0-20220202") == "go1.16"
// assert selectGoVersion("v5.0.1-20220202") == "go1.13"
// assert selectGoVersion("v5.3.2-20220202") == "go1.16"
// assert selectGoVersion("v5.4.0-20220202") == "go1.16"
// assert selectGoVersion("v6.2.1-20220202") == "go1.18"
// assert selectGoVersion("v6.2.1-20220203-somepoc") == "go1.18"
// assert selectGoVersion("v5.1.1") == "go1.16"
// assert selectGoVersion("v5.0.1") == "go1.13"

// assert selectGoVersion("release-6.3") == "go1.19"
// assert selectGoVersion("release-6.0") == "go1.18"
// assert selectGoVersion("release-5.2") == "go1.16"
// assert selectGoVersion("release-5.3") == "go1.16"
// assert selectGoVersion("release-5.4") == "go1.16"
// assert selectGoVersion("release-5.1") == "go1.16"
// assert selectGoVersion("release-5.0") == "go1.13"
// assert selectGoVersion("master") == "go1.19"
// assert selectGoVersion("release-4.0") == "go1.13"
// assert selectGoVersion("release-6.0-20220202") == "go1.18"
// assert selectGoVersion("release-5.2-20220203") == "go1.16"
// assert selectGoVersion("release-5.2-20220203-somepoc") == "go1.16"
// assert selectGoVersion("release-5.3-20220203-somepoc") == "go1.16"
// assert selectGoVersion("release-5.4-20220203-somepoc") == "go1.16"
// assert selectGoVersion("release-5.1-20220203-somepoc") == "go1.16"
// assert selectGoVersion("release-5.0-20220203-somepoc") == "go1.13"

// assert selectGoVersion("hz-poc") == "go1.16"
// assert selectGoVersion("ft-data-inconsistency") == "go1.16"
// assert selectGoVersion("br-stream") == "go1.16"

return this
