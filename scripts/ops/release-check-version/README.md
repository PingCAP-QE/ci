

# Check the version of the release packages
* docker images
* tiup online packages
* offline packages

# Prerequisites
* docker client: 20.10.4+
* python3.9+
* install the necessary python packages
    ```shell
    pip install -r requirements.txt
    ```

# What information will be checked
* docker images exist or not
* tiup online packages exist or not
* offline packages exist or not
* binary versions
  * release version
  * edition: community or enterprise
  * git commit hash


# Check docker images
## rc build check
* images list: pd | tikv | tidb | tiflash | br | dumpling | tidb-binlog | ticdc | dm | tidb-lightning | ng-monitoring | tidb-dashboard
* edition: community | enterprise

### check one image
example to check the rc build of tidb-binlog community edition
```shell
python3 check_docker_images.py  binlog v7.5.0 community --commit_hash "4a2ed99c466e02d0f441b742d7e62a1c67150f52"  --registry "hub.pingcap.net" --project qa
```
example to check the rc build of tidb-binlog Enterprise edition
```shell
python3 check_docker_images.py binlog v7.5.0 enterprise --commit_hash "4a2ed99c466e02d0f441b742d7e62a1c67150f52"  --registry "hub.pingcap.net" --project qa
```
### check batch images
example to check the rc build of all images
first, you need to prepare the components.json file, which contains the image list and the commit hash:
```json
{
  "docker_images": [
    {
      "name": "binlog",
      "version": "v7.5.0",
      "commit_hash": "4a2ed99c466e02d0f441b742d7e62a1c67150f52"
    }
  ],
  "tiup_packages": [
    {
    "name": "pd",
    "version": "v7.5.0",
    "commit_hash": "4a2ed99c466e02d0f441b742d7e62a1c67150f52"
    }
  ]
}
```
```shell
export IS_RC_BUILD=true
python3 main.py image --components_url='https://raw.githubusercontent.com/purelind/test-ci/main/components.json'
```


# Check tiup online package
## rc build check
* packages list: pd | tikv | tidb | tiflash | br | dumpling | pump | drainer | cdc | dm-master | dm-worker | dmctl | tidb-lightning | ng-monitoring | tidb-dashboard
### check one package
example to check the rc build of pd
```shell
 python3 check_tiup.py pd v7.5.0 --commit_hash="4a2ed99c466e02d0f441b742d7e62a1c67150f52" --is_tiup_staging
```
### check all package
```shell
export IS_RC_BUILD=true
python3 main.py tiup --components_url='https://raw.githubusercontent.com/purelind/test-ci/main/components.json'
```


# build the image
```shell
# build the single arch image
docker build -t hub.pingcap.net/jenkins/release-check-version:{tag} .
# build the multi-arch image
docker buildx build --platform linux/amd64,linux/arm64 -t hub.pingcap.net/jenkins/release-check-version:{tag}. --push
```
