TAG ?= latest
gethash:
	docker buildx build . -f Dockerfile --platform=linux/amd64,linux/arm64 --push -t hub.pingcap.net/jenkins/gethash:$(TAG)
