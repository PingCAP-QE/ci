releaseRepos = [
        "tidb",
        "tiflash",
        "ticdc",
        "dm",
        "tikv",
        "br",
        "lightning",
        "dumpling",
        "tidb-binlog",
        "pd"
]


def build_job(repo, source_image, target_image){
    def params = [
            [$class: 'StringParameterValue', name: 'SOURCE_IMAGE', value: source_image],
            [$class: 'StringParameterValue', name: 'TARGET_IMAGE', value: target_image]
    ]
    build job: 'jenkins-image-syncer',
            wait: true,
            parameters: params
}
def sync_images() {
    stage("parallel build") {
        builds = [:]
        for (repo in releaseRepos) {
            echo "${repo}"
            source_image = "hub.pingcap.net/image-sync/pingcap/${repo}:${RELEASE_TAG}"
            target_image = "hub.pingcap.net/${TARGET_PROJECT}/pingcap/${repo}:${RELEASE_TAG}"
            try {
                builds[repo] = build_job(repo, source_image, target_image)
            } catch(Exception e) {
                print(e)
                print("Sync failure:")
                print("     source_image: " + source_image)
                print("     target_image: " + target_image)
                continue
            }
        }
    }
    parallel builds
}


node("jenkins-image-syncer") {
    stage("image-sync-to-qa") {
        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
            sync_images()
        }
    }
}
