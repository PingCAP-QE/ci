/*
* @INPUT_BINARYS(string:binary url on fileserver, transfer througth atom jobs, Required)
* @REPO(string:repo name,eg tidb, Required)
* @PRODUCT(string:product name,eg tidb-ctl,if not set,default was the same as repo name, Optional)
* @ARCH(enumerate:arm64,amd64,Required)
* @OS(enumerate:linux,darwin,Required)
* @DOCKERFILE(string: url to download dockerfile, Optional)
* @RELEASE_TAG(string:for release workflow,what tag to release,Optional)
* @RELEASE_DOCKER_IMAGES(string:image to release seprate by comma, Required)
*/

properties([
        parameters([
                choice(
                        choices: ['arm64', 'amd64'],
                        name: 'ARCH'
                ),
                choice(
                        choices: ['linux', 'darwin'],
                        name: 'OS'
                ),
                string(
                        defaultValue: '',
                        name: 'INPUT_BINARYS',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'REPO',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRODUCT',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'DOCKERFILE',
                        trim: true
                ),
                
                string(
                        defaultValue: '',
                        name: 'RELEASE_DOCKER_IMAGES',
                        trim: true
                )
        ])
])

env.DOCKER_HOST = "tcp://localhost:2375"
env.DOCKER_REGISTRY = "docker.io"

if (params.PRODUCT.length() <= 1) {
    PRODUCT = REPO
}

// download binarys
binarys = params.INPUT_BINARYS.split(",")
def download() {
    for (item in binarys) {
        sh "curl ${FILE_SERVER_URL}/download/${item} | tar xz"
    }
}

// 构建出的镜像名称
imagePlaceHolder = UUID.randomUUID().toString()
// 使用非默认脚本构建镜像，构建出的镜像名称需要在下面定义 
if (PRODUCT == "tics" || PRODUCT == "tiflash" ) {
    if (RELEASE_TAG.length() > 1) {
        imagePlaceHolder = "hub.pingcap.net/tiflash/tiflash-server-centos7"
    }else {
        imagePlaceHolder = "hub.pingcap.net/tiflash/tiflash-ci-centos7"
    }
}

// 定义非默认的构建镜像脚本
buildImgagesh = [:]

buildImgagesh["dm_monitor_initializer"] = """
cd monitoring/
mv Dockerfile Dockerfile.bak || true
curl -C - --retry 5 --retry-delay 6 --retry-max-time 60 -o Dockerfile ${DOCKERFILE}
cat Dockerfile
docker build --pull  -t ${imagePlaceHolder} .
"""

buildImgagesh["tics"] = """
mv Dockerfile Dockerfile.bak || true
curl -C - --retry 5 --retry-delay 6 --retry-max-time 60 -o Dockerfile ${DOCKERFILE}
cat Dockerfile
if [[ "${RELEASE_TAG}" == "" ]]; then
    # No release tag, the image may be used in testings
    docker build --pull -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=1
else
    # Release tag provided, do not install test utils
    docker build --pull -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=0
fi
"""

buildImgagesh["tiflash"] = """
mv Dockerfile Dockerfile.bak || true
curl -C - --retry 5 --retry-delay 6 --retry-max-time 60 -o Dockerfile ${DOCKERFILE}
cat Dockerfile
if [[ "${RELEASE_TAG}" == "" ]]; then
    # No release tag, the image may be used in testings
    docker build --pull -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=1
else
    # Release tag provided, do not install test utils
    docker build --pull -t ${imagePlaceHolder} . --build-arg INSTALL_MYSQL=0
fi
"""


buildImgagesh["monitoring"] = """
docker build --pull -t ${imagePlaceHolder} .
"""

buildImgagesh["tiem"] = """
cp /usr/local/go/lib/time/zoneinfo.zip ./
docker build --pull  -t ${imagePlaceHolder} .
"""

buildImgagesh["tidb"] = """
cp /usr/local/go/lib/time/zoneinfo.zip ./
rm -rf tidb-server
cp bin/tidb-server ./
if [[ -f "bin/whitelist-1.so" ]]; then
    cp bin/whitelist-1.so ./
    echo "plugin file existed: whitelist-1.so"
fi
if [[ -f "bin/audit-1.so" ]]; then
    cp bin/audit-1.so ./
    echo "plugin file existed: audit-1.so"
fi
mv Dockerfile Dockerfile.bak || true
curl -C - --retry 5 --retry-delay 6 --retry-max-time 60 -o Dockerfile ${DOCKERFILE}
cat Dockerfile
docker build --pull  -t ${imagePlaceHolder} .
"""



def build_image() {
    // 如果构建脚本被定义了，使用定义的构建脚本
    if (buildImgagesh.containsKey(PRODUCT)) {
        sh buildImgagesh[PRODUCT]
    } else { // 如果没定义，使用默认构建脚本
        sh """
        rm -rf tmp-docker-build
        mkdir -p tmp-docker-build
        cd tmp-docker-build
        cp /usr/local/go/lib/time/zoneinfo.zip ./
        cp ../bin/* ./
        mv Dockerfile Dockerfile.bak || true
        curl -C - --retry 5 --retry-delay 6 --retry-max-time 60 -o Dockerfile ${DOCKERFILE}
        cat Dockerfile
        docker build --pull  -t ${imagePlaceHolder} .
        """
    }
}



def nodeLabel = "delivery"
def containerLabel = "delivery"
if (params.ARCH == "arm64") {
    nodeLabel = "arm"
    containerLabel = ""
}

images = params.RELEASE_DOCKER_IMAGES.split(",")
def release_images() {
    for (item in images) {
       if (item.startsWith("pingcap/")) {
        def harbor_tmp_image_name = "hub.pingcap.net/image-sync/" + item

        // This is for debugging
        // Debug ENV
        // def sync_dest_image_name = item.replace("pingcap/", "tidbdev/")
        // Prod ENV
        def sync_dest_image_name = item
        // End debugging

           docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
               sh """
               # Push to Internal Harbor First, then sync to DockerHub 
               # pingcap/tidb:v5.2.3 will be pushed to hub.pingcap.net/image-sync/pingcap/tidb:v5.2.3
               docker tag ${imagePlaceHolder} ${harbor_tmp_image_name}
               docker push ${harbor_tmp_image_name}
               """
           }

        sync_image_params = [
                string(name: 'triggered_by_upstream_ci', value: "docker-common-nova"),
                string(name: 'SOURCE_IMAGE', value: harbor_tmp_image_name),
                string(name: 'TARGET_IMAGE', value: sync_dest_image_name),
        ]
        build(job: "jenkins-image-syncer", parameters: sync_image_params, wait: true, propagate: true)
       }
       if (item.startsWith("hub.pingcap.net/")) {
           docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
               sh """
               docker tag ${imagePlaceHolder} ${item}
               docker push ${item}
               """
           }
       }
       if (item.startsWith("uhub.service.ucloud.cn/")) {
           docker.withRegistry("https://uhub.service.ucloud.cn", "ucloud-registry") {
               sh """
               docker tag ${imagePlaceHolder} ${item}
               docker push ${item}
               """
           }
       }
    }
    // 清理镜像
    sh "docker rmi ${imagePlaceHolder} || true"
}

def release() {
    deleteDir()
    download()
    build_image()
    release_images()
}

stage("Build & Release ${PRODUCT} image") {
    node(nodeLabel) {
        if (containerLabel != "") {
            container(containerLabel){
                release()
            }
        } else {
            release()
        }
    }
}