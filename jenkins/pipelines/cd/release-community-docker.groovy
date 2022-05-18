package cd
/*
* @RELEASE_TAG
* @RELEASE_BRANCH
* @TIDB_SHA
* @TIKV_SHA
* @PD_SHA
* @TIDB_LIGHTNING_SHA
* @BR_SHA
* @DUMPLING_SHA
* @TIDB_BINLOG_SHA
* @CDC_SHA
* @DM_SHA
* @TIFLASH_SHA
* @NG_MONITORING_SHA
*/


def libs
node('delivery') {
    container('delivery') {
        stage('prepare') {
            dir('centos7') {
                checkout scm
                libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
            }
            stage('community_docker_image_amd64') {
                println("community_docker_image_amd64")
                community_docker_image_amd64(libs)
            }

            stage('community_docker_image_arm64') {
                println("community_docker_image_arm64")
                community_docker_image_arm64(libs)
                build job: 'build-arm-image',
                        wait: true,
                        parameters: [
                                [$class: 'StringParameterValue', name: 'RELEASE_TAG', value: "${RELEASE_TAG}"],
                                [$class: 'StringParameterValue', name: 'RELEASE_BRANCH', value: "${RELEASE_BRANCH}"]
                        ]
            }
        }
    }
}

def community_docker_image_amd64(libs) {
    def os = "linux"
    def arch_amd64 = "amd64"
    def platform = "centos7"
    def builds = [:]
    builds = push_community_docker_image(libs, builds, arch_amd64, os, platform)

    // TODO: refine monitoring
    builds["Push monitor initializer"] = {
       libs.build_push_tidb_monitor_initializer_image()
    }
    parallel builds
}

def community_docker_image_arm64(libs) {
    def os = "linux"
    def arch_arm64 = "arm64"
    def platform = "centos7"
    def build_arms = [:]
    build_arms = push_community_docker_image(libs, build_arms, arch_arm64, os, platform)
    parallel build_arms
}

def push_community_docker_image(libs, builds, arch, os, platform) {
    builds["Push tidb Docker"] = {
//        println("community docker image params:" + TIDB_SHA + "/" + RELEASE_TAG)
        libs.release_online_image("tidb", TIDB_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push tikv Docker"] = {
        libs.release_online_image("tikv", TIKV_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push pd Docker"] = {
        libs.release_online_image("pd", PD_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push lightning Docker"] = {
        libs.release_online_image("tidb-lightning", TIDB_LIGHTNING_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push br Docker"] = {
        libs.release_online_image("br", BR_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push dumpling Docker"] = {
        libs.release_online_image("dumpling", DUMPLING_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push tidb-binlog Docker"] = {
        libs.release_online_image("tidb-binlog", TIDB_BINLOG_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["Push cdc Docker"] = {
        libs.release_online_image("ticdc", CDC_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }
    if (RELEASE_TAG >= "v5.3.0") {
        builds["Push dm Docker"] = {
            libs.release_online_image("dm", DM_SHA, arch, os, platform, RELEASE_TAG, false, false)
        }
    }
    builds["Push tiflash Docker"] = {
        libs.release_online_image("tiflash", TIFLASH_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }

    builds["NG Monitoring Docker"] = {
        libs.release_online_image("ng-monitoring", NG_MONITORING_SHA, arch, os, platform, RELEASE_TAG, false, false)
    }
    return builds
}
