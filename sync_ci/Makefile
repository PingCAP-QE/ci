GOENV	:= GO111MODULE=on
GO    	:= $(GOENV) go
BUILD_DIR := bin
INSTALL_DIR := /root/llh/server/sync-ci

default: binary

binary:
	$(GO) build -o $(BUILD_DIR)/sync-ci-service cmd/sync-ci-service/main.go

docker:
	docker build -t hub.pingcap.net/qa/respool -f build/package/Dockerfile .

define data
[Unit]
Description=sync-ci service
After=syslog.target network.target remote-fs.target nss-lookup.target

[Service]
User=root
ExecStart=${INSTALL_DIR}/sync-ci-service sync-ci -dsn "${dsn}"
Restart=always

[Install]
WantedBy=multi-user.target
endef
export data
install:
	@if [ "" = "${dsn}" ]; then echo "pls specify dsn";exit 1 ;fi
	@echo "$$data" > /etc/systemd/system/sync-ci.service

clean:
	rm -rf bin/

.PHONY: clean binary docker