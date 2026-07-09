TAG ?= latest
OCI_REGISTRY ?= hub.pingcap.net
gethash:
	docker buildx build . -f Dockerfile --platform=linux/amd64,linux/arm64 --push -t $(OCI_REGISTRY)/jenkins/gethash:$(TAG)
