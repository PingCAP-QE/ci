OCI_REGISTRY ?= hub.pingcap.net

default:
	echo "no default command"

image: normal-image tiflash-image

normal-image:
	# wget https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub
	# wget https://github.com/sgerrand/alpine-pkg-glibc/releases/download/2.32-r0/glibc-2.32-r0.apk
	docker build --tag=$(OCI_REGISTRY)/py/release-checker:master .

tiflash-image:
	docker build --tag=$(OCI_REGISTRY)/py/release-checker:tiflash --file Dockerfile.tiflash .

image-publish:
	docker push $(OCI_REGISTRY)/py/release-checker:master
	docker push $(OCI_REGISTRY)/py/release-checker:tiflash
