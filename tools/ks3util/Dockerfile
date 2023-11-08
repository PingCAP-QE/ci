FROM pingcap/pingcap-base:v1
RUN set -eux; \
	case `uname -m` in \
    x86_64) \
        url=https://ks3util-version-update.ks3-cn-beijing.ksyuncs.com/2.4.1/ks3util-linux-amd64; \
        ;; \
    aarch64) \
        url=http://192.168.233.30:8000/ks3util-linux-arm64; \
        ;; \
    *) \
        echo >&2 "error, not supported platform";exit 1; \
        ;; \
    esac; \
    curl -o /bin/ks3util "$url"; \
    chmod a+x /bin/ks3util
