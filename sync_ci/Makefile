GOENV	:= GO111MODULE=on
GO    	:= $(GOENV) go
BUILD_DIR := bin

default: binary

binary:
	$(GO) build -o $(BUILD_DIR)/sync-ci-service cmd/sync-ci-service/main.go

docker:
	docker build -t hub.pingcap.net/qa/respool -f build/package/Dockerfile .

clean:
	rm -rf bin/

.PHONY: clean binary docker