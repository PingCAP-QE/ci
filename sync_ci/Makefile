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
WorkingDirectory=$(INSTALL_DIR)
ExecStart=$(INSTALL_DIR)/sync-ci-service sync-ci -dsn "${dsn}" -cs "${cs}" -gh "${gh}" -tk "${tk}" -ui "${ui}" -wc "${wc}" -lp "$(INSTALL_DIR)/log" -rp  "$(INSTALL_DIR)/envrules.json"
Restart=always

[Install]
WantedBy=multi-user.target
endef
export data
install:
	@if [ "" = "${dsn}" ]; then echo "pls specify dsn";exit 1 ;fi
	@if [ "" = "${cs}" ]; then echo "pls specify -cs case-issue database dsn";exit 1 ;fi
	@if [ "" = "${gh}" ]; then echo "pls specify -gh github issue database dsn";exit 1 ;fi

	@mkdir -p $(INSTALL_DIR)
	@make binary
	@mv bin/sync-ci-service $(INSTALL_DIR)/sync-ci-service
	@cp pkg/parser/envrules.json $(INSTALL_DIR)/envrules.json
	@cp pkg/parser/regex_rules.json $(INSTALL_DIR)/regex_rules.json
	@echo "$$data" > /etc/systemd/system/sync-ci.service
	@systemctl enable sync-ci.service
	@systemctl daemon-reload

clean:
	rm -rf bin/

.PHONY: clean binary docker
