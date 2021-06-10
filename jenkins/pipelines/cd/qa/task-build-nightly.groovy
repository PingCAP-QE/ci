catchError {
    def ok = true
    stage('Build') {
        try {
            parallel(
                "tikv-master": {
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "master"), string(name: "MAKE_TARGET", value: "dist_release")])
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "master"), string(name: "MAKE_TARGET", value: "fail_release")])
                },
                "tikv-release-5.1": {
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-5.1"), string(name: "MAKE_TARGET", value: "dist_release")])
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-5.1"), string(name: "MAKE_TARGET", value: "fail_release")])
                },
                "tikv-release-5.0": {
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-5.0"), string(name: "MAKE_TARGET", value: "dist_release")])
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-5.0"), string(name: "MAKE_TARGET", value: "fail_release")])
                },
                "tikv-release-4.0": {
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-4.0"), string(name: "MAKE_TARGET", value: "dist_release")])
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-4.0"), string(name: "MAKE_TARGET", value: "fail_release")])
                },
                "tikv-release-3.x": {
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-3.1"), string(name: "MAKE_TARGET", value: "dist_release")])
                    build(job: "build-tikv", parameters: [string(name: "BRANCH", value: "release-3.0"), string(name: "MAKE_TARGET", value: "dist_release")])
                },
                "pd-all-releases": {
                    for (branch in ["release-3.0", "release-3.1", "release-4.0", "release-5.0", "release-5.1", "master"]) {
                        try {
                            def build_method
                            build_method = "go1.16.4-module"
                            if (["release-3.0", "release-3.1", "release-4.0", "release-5.0"].contains(branch)) {
                                build_method = "go1.13-module"
                            }
                            build(job: "build-pd", parameters: [string(name: "BRANCH", value: branch), string(name: "BUILD_METHOD", value: build_method)])
                            if (["release-4.0", "release-5.0", "release-5.1", "master"].contains(branch)) {
                                build(job: "build-pd", parameters: [string(name: "BRANCH", value: branch), string(name: "BUILD_METHOD", value: build_method), booleanParam(name: "FAILPOINT", value: true)])
                            }
                        } catch (e) { ok = false }
                    }
                    assert ok
                },
                "tidb-all-releases": {
                    for (branch in ["release-3.0", "release-3.1", "release-4.0", "release-5.0", "release-5.1", "master"]) {
                        try {
                            def build_method = "go1.16.4-module"
                            if (["release-3.0", "release-3.1", "release-4.0", "release-5.0"].contains(branch)) {
                                build_method = "go1.13-module"
                            }
                            build(job: "build-tidb", parameters: [string(name: "BRANCH", value: branch), string(name: "BUILD_METHOD", value: build_method)])
                            if (["release-4.0", "release-5.0", "release-5.1", "master"].contains(branch)) {
                                build(job: "build-tidb", parameters: [string(name: "BRANCH", value: branch), string(name: "BUILD_METHOD", value: build_method), booleanParam(name: "FAILPOINT", value: true)])
                            }
                        } catch (e) { ok = false }
                    }
                    assert ok
                },
            )
        } catch (e) { ok = false }
    }

    assert ok
}
