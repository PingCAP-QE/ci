default:
	echo "no default command"

image: normal-image tiflash-image

normal-image:
	# wget https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub
	# wget https://github.com/sgerrand/alpine-pkg-glibc/releases/download/2.32-r0/glibc-2.32-r0.apk
	docker build --tag=hub.pingcap.net/py/release-checker:master .

tiflash-image:
	docker build --tag=hub.pingcap.net/py/release-checker:tiflash --file Dockerfile.tiflash .

image-publish:
	docker push hub.pingcap.net/py/release-checker:master
	docker push hub.pingcap.net/py/release-checker:tiflash
